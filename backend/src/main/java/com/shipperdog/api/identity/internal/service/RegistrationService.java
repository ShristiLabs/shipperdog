package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.config.AuthProperties;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.entity.UserInvitationEntity;
import com.shipperdog.api.identity.internal.repository.UserInvitationRepository;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.organization.OrgRole;
import com.shipperdog.api.organization.OrganizationAccess;
import com.shipperdog.api.organization.OrganizationException;
import com.shipperdog.api.tester.TesterOnboarding;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitation-only registration: an authenticated user invites an address,
 * the invitee presents the one-time invitation token plus a chosen
 * password, and the account is created ACTIVE. Re-inviting an address
 * revokes its previous pending invitation. Since S1-04, an invitation may
 * carry an organization and role: accepting such an invitation grants the
 * membership with the recorded role. Since S1-05 the inviter's
 * permission (org:members:invite) is enforced by the controller's
 * {@code @PreAuthorize}; the service keeps only the organization
 * existence check.
 */
@Service
public class RegistrationService {

    static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_PASSWORD_LENGTH = 128;

    private final UserRepository users;
    private final UserInvitationRepository invitations;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final InvitationStateRecorder invitationState;
    private final AuditEventPublisher audit;
    private final OrganizationAccess organizations;
    private final TesterOnboarding testerOnboarding;

    RegistrationService(
            UserRepository users,
            UserInvitationRepository invitations,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            Clock clock,
            InvitationStateRecorder invitationState,
            AuditEventPublisher audit,
            OrganizationAccess organizations,
            TesterOnboarding testerOnboarding) {
        this.users = users;
        this.invitations = invitations;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.invitationState = invitationState;
        this.audit = audit;
        this.organizations = organizations;
        this.testerOnboarding = testerOnboarding;
    }

    /** Creates a platform invitation (no organization context). */
    @Transactional
    public Invitation createInvitation(UUID invitedBy, String rawEmail) {
        return createInvitation(invitedBy, rawEmail, null, null);
    }

    /**
     * Creates an invitation, optionally scoped to an organization with the
     * role granted on acceptance. Org-scoped invitations require the
     * inviter to be that organization's administrator (or a platform
     * administrator).
     */
    @Transactional
    public Invitation createInvitation(UUID invitedBy, String rawEmail, UUID organizationId, OrgRole role) {
        String email = AuthService.normalizeEmail(rawEmail);
        requireValidEmail(email);
        if (organizationId == null && role != null) {
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "role requires organizationId");
        }
        if (organizationId != null) {
            requireInvitationPermission(organizationId);
        }
        OrgRole grantedRole = organizationId == null ? null : (role == null ? OrgRole.ORG_MEMBER : role);
        Instant now = Instant.now(clock);
        // Tester-flavored invitations (issue #26) are revoked per tester
        // record by the tester module's mint path; the address-wide
        // revoke here must not kill a tester's live invitation.
        invitations
                .findByEmailAndStatusAndTesterIdIsNull(email, UserInvitationEntity.Status.PENDING)
                .forEach(pending -> {
                    pending.setStatus(UserInvitationEntity.Status.REVOKED);
                    invitations.save(pending);
                });
        String rawToken = SecureTokens.randomToken();
        UserInvitationEntity invitation = new UserInvitationEntity(
                email,
                invitedBy,
                SecureTokens.sha256Hex(rawToken),
                now.plus(properties.invitationTtl()),
                organizationId,
                grantedRole);
        invitations.save(invitation);
        Map<String, String> detail = new HashMap<>();
        if (organizationId != null) {
            detail.put("organizationId", organizationId.toString());
            detail.put("role", grantedRole.name());
        }
        audit.publish(new AuditEvent(
                AuditEventType.INVITATION_CREATED, Instant.now(clock), invitedBy, organizationId, null, true, detail));
        return new Invitation(email, rawToken, invitation.getExpiresAt(), organizationId, grantedRole);
    }

    /** Accepts an invitation and creates the ACTIVE user account. */
    @Transactional
    public UserEntity register(String rawInvitationToken, String rawEmail, String password, String fullName) {
        String email = AuthService.normalizeEmail(rawEmail);
        requireValidEmail(email);
        requirePassword(password);
        String name = fullName == null ? "" : fullName.trim();
        if (name.isEmpty() || name.length() > 200) {
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "fullName is required (max 200 chars)");
        }
        if (rawInvitationToken == null || rawInvitationToken.isBlank()) {
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "invitationToken is required");
        }
        UserInvitationEntity invitation = invitations
                .findByTokenHash(SecureTokens.sha256Hex(rawInvitationToken))
                .orElseThrow(this::invalidInvitation);
        Instant now = Instant.now(clock);
        if (invitation.getStatus() != UserInvitationEntity.Status.PENDING) {
            throw invalidInvitation();
        }
        if (invitation.getExpiresAt().isBefore(now)) {
            // REQUIRES_NEW: the marking must survive the rollback this throw causes.
            invitationState.markExpired(invitation.getId());
            throw invalidInvitation();
        }
        if (!invitation.getEmail().equals(email)) {
            throw invalidInvitation();
        }
        if (users.existsByEmail(email)) {
            throw new AuthException(AuthException.Code.EMAIL_TAKEN, "address is already registered");
        }
        UserEntity user = users.save(new UserEntity(email, passwordEncoder.encode(password), name));
        invitation.setStatus(UserInvitationEntity.Status.ACCEPTED);
        invitation.setAcceptedAt(now);
        invitations.save(invitation);
        if (invitation.getTesterId() != null) {
            // Tester onboarding (issue #26): the same endpoint IS the
            // accept flow — activation links the account and flips the
            // tester INVITED -> ACTIVE; no organization membership is
            // granted (tenant attachment is S6-03).
            try {
                testerOnboarding.onInvitationAccepted(
                        invitation.getTesterId(), user.getId(), invitation.getInvitedBy());
            } catch (TesterOnboarding.NotOnboardingException e) {
                // The tester record vanished or left INVITED after the
                // invitation was minted; the registration fails atomically
                // with the same generic error as any dead token.
                throw invalidInvitation();
            }
        } else if (invitation.getOrganizationId() != null) {
            try {
                organizations.addMember(
                        invitation.getOrganizationId(), user.getId(), invitation.getRole(), invitation.getInvitedBy());
            } catch (OrganizationException e) {
                // The organization context became invalid after the invitation
                // was created (e.g. deactivation); the registration fails
                // atomically with the same generic error as any dead token.
                throw invalidInvitation();
            }
        }
        audit(AuditEventType.USER_REGISTERED, user.getId(), true, null);
        return user;
    }

    /**
     * Existence check for org-scoped invitations. Permission
     * (org:members:invite) is enforced upstream by the controller
     * annotation (S1-05); unauthenticated callers never reach the
     * service, and unauthorized callers already received the opaque 403.
     */
    private void requireInvitationPermission(UUID organizationId) {
        if (!organizations.organizationExists(organizationId)) {
            throw new AuthException(AuthException.Code.NOT_FOUND, "organization not found");
        }
    }

    private void requireValidEmail(String email) {
        if (email == null || email.length() > 255 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "a valid email address is required");
        }
    }

    private void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new AuthException(
                    AuthException.Code.WEAK_PASSWORD,
                    "password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters");
        }
    }

    private AuthException invalidInvitation() {
        return new AuthException(
                AuthException.Code.INVALID_INVITATION, "invitation is invalid, expired, or already used");
    }

    private void audit(AuditEventType type, UUID userId, boolean success, Map<String, String> detail) {
        audit.publish(new AuditEvent(type, Instant.now(clock), userId, null, null, success, detail));
    }

    /** Created invitation; {@code rawToken} is shown exactly once. */
    public record Invitation(String email, String rawToken, Instant expiresAt, UUID organizationId, OrgRole role) {}
}

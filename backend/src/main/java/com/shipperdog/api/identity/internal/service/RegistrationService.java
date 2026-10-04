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
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invitation-only registration: an authenticated user invites an address,
 * the invitee presents the one-time invitation token plus a chosen
 * password, and the account is created ACTIVE. Re-inviting an address
 * revokes its previous pending invitation.
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

    RegistrationService(
            UserRepository users,
            UserInvitationRepository invitations,
            PasswordEncoder passwordEncoder,
            AuthProperties properties,
            Clock clock,
            InvitationStateRecorder invitationState,
            AuditEventPublisher audit) {
        this.users = users;
        this.invitations = invitations;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.invitationState = invitationState;
        this.audit = audit;
    }

    /** Creates an invitation; the raw token is returned exactly once. */
    @Transactional
    public Invitation createInvitation(UUID invitedBy, String rawEmail) {
        String email = AuthService.normalizeEmail(rawEmail);
        requireValidEmail(email);
        Instant now = Instant.now(clock);
        invitations
                .findByEmailAndStatus(email, UserInvitationEntity.Status.PENDING)
                .forEach(pending -> {
                    pending.setStatus(UserInvitationEntity.Status.REVOKED);
                    invitations.save(pending);
                });
        String rawToken = SecureTokens.randomToken();
        UserInvitationEntity invitation = new UserInvitationEntity(
                email, invitedBy, SecureTokens.sha256Hex(rawToken), now.plus(properties.invitationTtl()));
        invitations.save(invitation);
        audit(AuditEventType.INVITATION_CREATED, invitedBy, true, null);
        return new Invitation(email, rawToken, invitation.getExpiresAt());
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
        audit(AuditEventType.USER_REGISTERED, user.getId(), true, null);
        return user;
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
    public record Invitation(String email, String rawToken, Instant expiresAt) {}
}

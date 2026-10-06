package com.shipperdog.api.identity.internal.service;

import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventPublisher;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.identity.TesterInvitations;
import com.shipperdog.api.identity.internal.crypto.SecureTokens;
import com.shipperdog.api.identity.internal.entity.UserInvitationEntity;
import com.shipperdog.api.identity.internal.repository.UserInvitationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tester-flavor arm of the invitation machinery (issue #26). Lives
 * in identity because the {@code user_invitations} table, digest
 * scheme, and accept endpoint do; the tester module drives it through
 * the public {@link TesterInvitations} surface only. A SEPARATE bean
 * from {@link RegistrationService} by design: registration consumes
 * the tester module's onboarding callback, so a single bean would
 * close a bean-dependency cycle.
 *
 * <p>Revocation semantics mirror registration's: minting revokes the
 * tester's previous pending invitation first (the tester surface calls
 * mint only on first invite and re-invite, and the partial unique
 * index {@code uq_user_invitations_pending_tester} collapses concurrent
 * mints onto one live row).
 */
@Service
public class TesterInvitationService implements TesterInvitations {

    private final UserInvitationRepository invitations;
    private final Clock clock;
    private final AuditEventPublisher audit;

    TesterInvitationService(UserInvitationRepository invitations, Clock clock, AuditEventPublisher audit) {
        this.invitations = invitations;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    @Transactional
    public Invitation mintForTester(UUID organizationId, UUID testerId, String email, UUID invitedBy, Duration ttl) {
        invitations
                .findByTesterIdAndStatus(testerId, UserInvitationEntity.Status.PENDING)
                .ifPresent(pending -> {
                    pending.setStatus(UserInvitationEntity.Status.REVOKED);
                    // Flush the revocation BEFORE the replacement insert:
                    // Hibernate's action ordering runs inserts before updates,
                    // and the partial unique index uq_user_invitations_pending_tester
                    // would otherwise see two live PENDING rows at the insert.
                    invitations.saveAndFlush(pending);
                });
        Instant expiresAt = Instant.now(clock).plus(ttl);
        String rawToken = SecureTokens.randomToken();
        // Flush the replacement insert too, so a concurrent mint that
        // lost the uq_user_invitations_pending_tester race throws HERE
        // (inside the caller's transaction) instead of at commit.
        invitations.saveAndFlush(new UserInvitationEntity(
                email, invitedBy, SecureTokens.sha256Hex(rawToken), expiresAt, organizationId, null, testerId));
        audit.publish(new AuditEvent(
                AuditEventType.INVITATION_CREATED,
                Instant.now(clock),
                invitedBy,
                organizationId,
                null,
                true,
                Map.of("testerId", testerId.toString(), "flavor", "tester")));
        return new Invitation(email, rawToken, expiresAt);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PendingInvitation> pendingForTester(UUID testerId) {
        return invitations
                .findByTesterIdAndStatus(testerId, UserInvitationEntity.Status.PENDING)
                .map(pending ->
                        new PendingInvitation(pending.getExpiresAt(), pending.getInvitedBy(), pending.getCreatedAt()));
    }
}

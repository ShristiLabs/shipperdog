package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.identity.TesterInvitations;
import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.internal.entity.TesterEntity;
import com.shipperdog.api.tester.internal.repository.TesterRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the tester a concurrent insert beat us to
 * (uq_testers_organization_email) after a constraint violation, without
 * parsing driver messages. Everything runs in its own transaction on
 * purpose: PostgreSQL aborts the failed write's transaction, so any
 * re-derivation on that transaction dies with "current transaction is
 * aborted" — a fresh transaction sees the concurrent winner's
 * committed row, including the invitation state (the race discipline
 * of the application registry and tenant guards, issues #13/#9,
 * applied to tester creation). Creation is idempotent: the winner IS
 * the answer, so the caller returns it as the existing tester rather
 * than surfacing the race.
 */
@Component
public class TesterConflictResolver {

    private final TesterRepository testers;

    private final TesterInvitations invitations;

    TesterConflictResolver(TesterRepository testers, TesterInvitations invitations) {
        this.testers = testers;
        this.invitations = invitations;
    }

    /** The concurrent winner's tester plus its live invitation state, if it exists. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Existing> winnerOf(UUID organizationId, String email) {
        return testers.findByOrganizationIdAndEmail(organizationId, email)
                .map(tester -> new Existing(
                        tester,
                        invitations
                                .pendingForTester(tester.getId())
                                .map(TesterInvitations.PendingInvitation::expiresAt)
                                .orElse(null)));
    }

    /** Fallback when the violation was not the email guard after all. */
    public TesterException unexpected(UUID organizationId) {
        return new TesterException(
                TesterException.Code.EMAIL_TAKEN,
                "a tester with this email already exists in the organization " + organizationId);
    }

    /** Winner view: the tester record and its live invitation's expiry (null when none). */
    public record Existing(TesterEntity tester, Instant pendingExpiresAt) {}
}

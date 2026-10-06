package com.shipperdog.api.tester.internal.service;

import com.shipperdog.api.tester.TesterException;
import com.shipperdog.api.tester.internal.entity.TenantTesterMembershipEntity;
import com.shipperdog.api.tester.internal.repository.TenantTesterMembershipScopedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fresh-transaction re-derivation of a tenant-tester membership after
 * a lost insert race (issue #28) — the TesterConflictResolver pattern
 * from issue #26: the racing transaction is aborted and cannot answer
 * probes (PostgreSQL), so the winner is read on a NEW transaction
 * (REQUIRES_NEW) once the attempt's rollback has completed. Runs
 * through the seam like every endpoint read — the link route's tenant
 * context is still bound on this thread.
 */
@Component
public class TenantTesterMembershipConflictResolver {

    private final TenantTesterMembershipScopedRepository memberships;

    TenantTesterMembershipConflictResolver(TenantTesterMembershipScopedRepository memberships) {
        this.memberships = memberships;
    }

    /**
     * The concurrent winner of the {@code (tenant, tester)} pair, or
     * empty when the race loser cannot be explained by a membership
     * (the row vanished mid-flight; the caller renders a conflict).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TenantTesterMembershipEntity> winnerOf(UUID tenantId, UUID testerId) {
        return memberships.findOneScoped(testerEquals(testerId));
    }

    /** The inexplicable-race answer: an idempotent surface never 500s on uniqueness. */
    public TesterException unexplained(UUID tenantId, UUID testerId) {
        return new TesterException(
                TesterException.Code.MEMBERSHIP_NOT_FOUND,
                "tenant tester membership state changed concurrently (" + tenantId + "/" + testerId + ")");
    }

    /** Membership-by-tester condition, composed with the seam's tenant predicate. */
    private static Specification<TenantTesterMembershipEntity> testerEquals(UUID testerId) {
        return (root, query, builder) -> builder.equal(root.get("testerId"), testerId);
    }
}

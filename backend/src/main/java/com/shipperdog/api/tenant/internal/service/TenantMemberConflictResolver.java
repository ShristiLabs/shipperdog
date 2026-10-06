package com.shipperdog.api.tenant.internal.service;

import com.shipperdog.api.tenant.internal.entity.TenantMemberEntity;
import com.shipperdog.api.tenant.internal.repository.TenantMemberScopedRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fresh-transaction re-derivation of a tenant member after a lost
 * insert race (issue #28) — the TesterConflictResolver pattern from
 * issue #26: the racing transaction is aborted and cannot answer
 * probes (PostgreSQL), so the winner is read on a NEW transaction
 * (REQUIRES_NEW) once the attempt's rollback has completed. Runs
 * through the seam like every endpoint read — the member route's
 * tenant context is still bound on this thread.
 */
@Component
public class TenantMemberConflictResolver {

    private final TenantMemberScopedRepository members;

    TenantMemberConflictResolver(TenantMemberScopedRepository members) {
        this.members = members;
    }

    /**
     * The concurrent winner of the {@code (tenant, user)} pair, or
     * empty when the race loser cannot be explained by a membership
     * row (the caller renders the conflict).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TenantMemberEntity> winnerOf(UUID targetUserId) {
        return members.findOneScoped(userEquals(targetUserId));
    }

    /** Membership-by-user condition, composed with the seam's tenant predicate. */
    private static Specification<TenantMemberEntity> userEquals(UUID userId) {
        return (root, query, builder) -> builder.equal(root.get("userId"), userId);
    }
}

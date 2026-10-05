package com.shipperdog.api.application.internal.service;

import com.shipperdog.api.application.internal.entity.TenantApplicationAuthorizationEntity;
import com.shipperdog.api.application.internal.repository.TenantApplicationAuthorizationRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives the outcome of a lost concurrent-grant race (issue #15,
 * S3-03) after the pair's unique-row constraint fires. The probe runs
 * in its own transaction on purpose: PostgreSQL aborts the failed
 * write's transaction, so a re-derivation query on that transaction
 * would die with "current transaction is aborted" and surface as a 500
 * — the exact failure the S3-03 concurrency test caught on CI timing
 * after issue #17. A fresh transaction sees the concurrent winner's
 * committed row and classifies the outcome; the constraint index
 * remains the final authority on the race itself.
 *
 * <p>PostgreSQL guarantees the winner is committed by the time the
 * loser's insert fails — an insert against a conflicting uncommitted
 * row blocks until the holder commits or aborts, and only then errors
 * — so the fresh read cannot miss a winner that exists.
 */
@Component
public class AuthorizationConflictResolver {

    private final TenantApplicationAuthorizationRepository authorizations;

    AuthorizationConflictResolver(TenantApplicationAuthorizationRepository authorizations) {
        this.authorizations = authorizations;
    }

    /**
     * The pair's committed row as a fresh transaction sees it, or empty
     * when no row exists.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<TenantApplicationAuthorizationEntity> committedGrant(UUID applicationId, UUID tenantId) {
        return authorizations.findByApplicationIdAndTenantId(applicationId, tenantId);
    }
}

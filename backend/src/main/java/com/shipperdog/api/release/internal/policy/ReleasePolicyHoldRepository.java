package com.shipperdog.api.release.internal.policy;

import com.shipperdog.api.release.internal.policy.ReleasePolicyHoldEntity.Status;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the hold ledger (V34). A plain {@link JpaRepository}
 * (the V25 child doctrine): the parent release is resolved through
 * the release module's scoped port first, so isolation is enforced at
 * the parent and never re-derived here.
 */
public interface ReleasePolicyHoldRepository extends JpaRepository<ReleasePolicyHoldEntity, UUID> {

    /** The release's holds, oldest first — the pending demands and the acknowledged history beside them. */
    List<ReleasePolicyHoldEntity> findByReleaseIdOrderByCreatedAtAscIdAsc(UUID releaseId);

    /** The (release, rule, ring) triple's hold — the unique constraint's standing question. */
    Optional<ReleasePolicyHoldEntity> findByReleaseIdAndRuleIdAndRing(UUID releaseId, UUID ruleId, String ring);

    /** The delete guard's question: whether any hold references the rule (the ledger is audit evidence). */
    boolean existsByRuleId(UUID ruleId);

    /** The pending demands of a release (the acknowledge surface's live work list). */
    List<ReleasePolicyHoldEntity> findByReleaseIdAndStatusOrderByCreatedAtAscIdAsc(UUID releaseId, Status status);
}

package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaAlertFiringEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The firing rows (S16-11): a plain repository addressed by the
 * rule's id -- the {@code OtaRolloutEventRepository} doctrine. The
 * rows carry no scope dimension of their own by design: every caller
 * resolves the owning rule through the org-scoped seam first and
 * reaches the firings by the guarded rule's id only. (The tenant
 * dashboard's feed joins rules to firings in one bounded statement
 * over the tables directly, the S11-04 read-model discipline -- it
 * never touches this repository.)
 */
public interface OtaAlertFiringRepository extends JpaRepository<OtaAlertFiringEntity, UUID> {

    /** The rule's standing firing, when one exists (one per rule by construction). */
    Optional<OtaAlertFiringEntity> findByRuleIdAndResolvedAtIsNull(UUID ruleId);

    /** The rule's firing history, newest first, bounded by the pageable. */
    List<OtaAlertFiringEntity> findByRuleIdOrderByFiredAtDescIdDesc(UUID ruleId, Pageable pageable);

    /** Whether the rule holds any firing at all (the delete guard: history stays addressable). */
    boolean existsByRuleId(UUID ruleId);
}

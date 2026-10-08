package com.shipperdog.api.release.internal.policy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the evaluation log (V34). Append-only history: no
 * update or delete path exists anywhere in the module.
 */
public interface ReleasePolicyEvaluationRepository extends JpaRepository<ReleasePolicyEvaluationEntity, UUID> {

    /** The release's evaluation history, newest first (the audit read the dashboard renders). */
    List<ReleasePolicyEvaluationEntity> findByReleaseIdOrderByOccurredAtDescIdAsc(UUID releaseId);
}

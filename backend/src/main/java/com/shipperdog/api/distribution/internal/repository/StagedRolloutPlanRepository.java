package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The staged rollout plan rows (V48, S13-06, #66). Plain repository —
 * the staged surface is organization-addressed (the plan's own
 * organization check is the read's guard, the standing of every
 * distribution surface).
 */
public interface StagedRolloutPlanRepository extends JpaRepository<StagedRolloutPlanEntity, UUID> {

    /** The release's one plan (the grain's unique answer; the dispatch and gate reads). */
    Optional<StagedRolloutPlanEntity> findByReleaseId(UUID releaseId);

    /** The organization-guarded resolution (the concealed miss across organizations). */
    Optional<StagedRolloutPlanEntity> findByReleaseIdAndOrganizationId(UUID releaseId, UUID organizationId);

    /** The plans by version (the download gate's addressing grain). */
    List<StagedRolloutPlanEntity> findByVersionId(UUID versionId);

    /** The plans of one standing (the scheduled advancement pass's input). */
    List<StagedRolloutPlanEntity> findByStatus(StagedRolloutPlanEntity.Status status);
}

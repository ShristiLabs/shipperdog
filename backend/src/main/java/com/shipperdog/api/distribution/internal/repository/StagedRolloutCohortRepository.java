package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutCohortEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The staged rollout cohort rows (V48, S13-06, #66): the deterministic resolution, stored. */
public interface StagedRolloutCohortRepository extends JpaRepository<StagedRolloutCohortEntity, UUID> {

    /** One stage's cohort (the inspectable membership, AC1). */
    List<StagedRolloutCohortEntity> findByStageIdOrderByRankAsc(UUID stageId);

    /** Every cohort row of a plan's stages, in one read (the reached-set resolution). */
    List<StagedRolloutCohortEntity> findByStageIdIn(Collection<UUID> stageIds);
}

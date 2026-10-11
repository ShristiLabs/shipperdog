package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutStageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The staged rollout stage rows (V48, S13-06, #66), position-ordered per plan. */
public interface StagedRolloutStageRepository extends JpaRepository<StagedRolloutStageEntity, UUID> {

    /** The plan's stages in widening order. */
    List<StagedRolloutStageEntity> findByPlanIdOrderByPositionAsc(UUID planId);
}

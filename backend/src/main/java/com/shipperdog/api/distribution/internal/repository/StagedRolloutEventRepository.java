package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The staged rollout timeline rows (V48, S13-06, #66). Insert-and-read
 * only — the timeline is append-only (the {@code channel_rollout_steps}
 * precedent).
 */
public interface StagedRolloutEventRepository extends JpaRepository<StagedRolloutEventEntity, UUID> {

    /** The plan's timeline in occurrence order (the dashboard's first-class read). */
    List<StagedRolloutEventEntity> findByPlanIdOrderByOccurredAtAscIdAsc(UUID planId);
}

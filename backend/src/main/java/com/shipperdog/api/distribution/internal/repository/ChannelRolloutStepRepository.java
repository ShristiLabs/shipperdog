package com.shipperdog.api.distribution.internal.repository;

import com.shipperdog.api.distribution.internal.entity.ChannelRolloutStepEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The rollout timeline rows (V47, S13-05, #65), read per rollout in occurrence order. */
public interface ChannelRolloutStepRepository extends JpaRepository<ChannelRolloutStepEntity, UUID> {

    /** The rollout's timeline, oldest first (the dashboard's rendering order). */
    List<ChannelRolloutStepEntity> findByRolloutIdOrderByOccurredAtAscIdAsc(UUID rolloutId);
}

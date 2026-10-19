package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaRolloutEventEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The rollout timeline rows (S16-06): a plain, append-only repository
 * addressed by the plan's id -- the V48 {@code
 * staged_rollout_events} doctrine. Rows are inserted by the control
 * acts and never updated or deleted; every caller resolves the plan
 * through the org-scoped seam first and addresses the timeline by the
 * guarded plan's id only.
 */
public interface OtaRolloutEventRepository extends JpaRepository<OtaRolloutEventEntity, UUID> {

    /** The plan's timeline, oldest first, the id breaking same-moment ties. */
    List<OtaRolloutEventEntity> findByPlanIdOrderByOccurredAtAscIdAsc(UUID planId);
}

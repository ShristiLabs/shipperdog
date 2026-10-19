package com.shipperdog.api.otabundle.internal.repository;

import com.shipperdog.api.otabundle.internal.entity.OtaRolloutStageEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The rollout stage rows (S16-06): a plain repository addressed by the
 * plan's id -- the V48 {@code staged_rollout_stages} doctrine. The
 * rows carry no owning-dimension columns of their own (V56's mirror of
 * V48), so isolation is NOT this repository's to enforce: every caller
 * resolves the plan through the org-scoped seam first and addresses
 * the children by the guarded plan's id only.
 */
public interface OtaRolloutStageRepository extends JpaRepository<OtaRolloutStageEntity, UUID> {

    /** The plan's stages in widening order (position ascending). */
    List<OtaRolloutStageEntity> findByPlanIdOrderByPositionAsc(UUID planId);
}

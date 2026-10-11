package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutStageEntity;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutCohortRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutPlanRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutStageRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staged rollout's standing reads (issue #66, S13-06): the
 * repository-backed resolution every consumer inside the module asks
 * — the dispatch's target narrowing, the Play curve's staged initial
 * percentage, and the download gate's reached set — so each consumer
 * holds one shared definition of "where the plan stands" and none
 * re-derives it. Deliberately free of service dependencies (the
 * repositories only): the reader is the leaf every staged consumer
 * composes without a cycle.
 */
@Component
public class StagedRolloutReader {

    private final StagedRolloutPlanRepository plans;
    private final StagedRolloutStageRepository stages;
    private final StagedRolloutCohortRepository cohorts;

    public StagedRolloutReader(
            StagedRolloutPlanRepository plans,
            StagedRolloutStageRepository stages,
            StagedRolloutCohortRepository cohorts) {
        this.plans = plans;
        this.stages = stages;
        this.cohorts = cohorts;
    }

    /**
     * The plan's standing while it narrows distribution: a RUNNING
     * plan's serving stage and REACHED cohort (the union of the
     * cohorts of stages 1..current). Empty for every other standing —
     * a PLANNED plan narrows nothing (the release is not
     * distributing), a COMPLETE plan reaches everyone, a ROLLED_BACK
     * plan's cut is the release state's own.
     */
    @Transactional(readOnly = true)
    public Optional<Standing> narrowingStandingOf(UUID releaseId) {
        return plans.findByReleaseId(releaseId)
                .filter(plan -> plan.getStatus() == StagedRolloutPlanEntity.Status.RUNNING)
                .flatMap(plan -> standingOf(plan, null));
    }

    /**
     * ANY plan of the release (the manifest freeze and the view resolve
     * their own standing shapes from it).
     */
    @Transactional(readOnly = true)
    public Optional<StagedRolloutPlanEntity> planOf(UUID releaseId) {
        return plans.findByReleaseId(releaseId);
    }

    /**
     * The reached set of stages 1..through, over the plan's stage and
     * cohort rows — one read per call, index-served.
     */
    @Transactional(readOnly = true)
    public Set<UUID> reachedTenantIdsOf(UUID planId, Integer throughStage) {
        List<StagedRolloutStageEntity> reached = stages.findByPlanIdOrderByPositionAsc(planId).stream()
                .filter(stage -> stage.getStatus() == StagedRolloutStageEntity.Status.REACHED)
                .filter(stage -> throughStage == null || stage.getPosition() <= throughStage)
                .toList();
        Set<UUID> tenants = new LinkedHashSet<>();
        if (!reached.isEmpty()) {
            cohorts.findByStageIdIn(reached.stream()
                            .map(StagedRolloutStageEntity::getId)
                            .toList())
                    .forEach(row -> tenants.add(row.getTenantId()));
        }
        return tenants;
    }

    private Optional<Standing> standingOf(StagedRolloutPlanEntity plan, Integer throughStage) {
        if (plan.getCurrentStage() == null) {
            return Optional.empty();
        }
        Set<UUID> reached = reachedTenantIdsOf(plan.getId(), throughStage);
        return Optional.of(new Standing(plan, plan.getCurrentStage(), reached));
    }

    /**
     * Where a RUNNING plan stands: the plan row, the serving stage
     * (1-based), and the reached cohort (the tenants the release
     * serves at this standing).
     */
    public record Standing(StagedRolloutPlanEntity plan, int currentStage, Set<UUID> reachedTenantIds) {}
}

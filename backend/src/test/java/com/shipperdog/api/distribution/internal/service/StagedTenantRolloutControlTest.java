package com.shipperdog.api.distribution.internal.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.internal.apple.AppStorePhasedReleaseService;
import com.shipperdog.api.distribution.internal.apple.TestFlightRolloutArms;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutCohortEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutStageEntity;
import com.shipperdog.api.distribution.internal.google.PlayRolloutService;
import com.shipperdog.api.distribution.internal.repository.ChannelDistributionRepository;
import com.shipperdog.api.distribution.internal.repository.ChannelGroupLinkRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutCohortRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutEventRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutPlanRepository;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutStageRepository;
import com.shipperdog.api.release.ReleaseAccess;
import com.shipperdog.api.release.ReleaseRolloutPolicyGate;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * The staged action's concurrent-edit translation's own proof (issue
 * #66, S13-06): a control that loses the optimistic-stamp race answers
 * the precise 409 {@code DISTRIBUTION_STAGED_ROLLOUT_CONFLICT} —
 * never a generic 500 and never a silent last-write-win over the
 * winner's stage. The race's read-write window is deterministic only
 * at this seam (the plan save throwing the version conflict), the
 * same shape the play curve's own proof pins.
 */
class StagedTenantRolloutControlTest {

    private StagedRolloutPlanRepository plans;
    private StagedRolloutStageRepository stageRows;
    private StagedRolloutCohortRepository cohortRows;
    private StagedRolloutEventRepository timeline;
    private ChannelDistributionRepository records;
    private ReleaseAccess releases;
    private StagedTenantRolloutService service;

    private final UUID org = UUID.randomUUID();
    private final UUID release = UUID.randomUUID();
    private StagedRolloutPlanEntity plan;

    @BeforeEach
    void setUp() {
        plans = mock(StagedRolloutPlanRepository.class);
        stageRows = mock(StagedRolloutStageRepository.class);
        cohortRows = mock(StagedRolloutCohortRepository.class);
        timeline = mock(StagedRolloutEventRepository.class);
        records = mock(ChannelDistributionRepository.class);
        releases = mock(ReleaseAccess.class);
        service = new StagedTenantRolloutService(
                plans,
                stageRows,
                cohortRows,
                timeline,
                records,
                mock(ChannelGroupLinkRepository.class),
                releases,
                mock(ReleaseRolloutPolicyGate.class),
                mock(PlayRolloutService.class),
                mock(AppStorePhasedReleaseService.class),
                mock(TestFlightRolloutArms.class),
                mock(StagedRolloutBlockRecorder.class),
                mock(AuditLogger.class),
                Clock.systemUTC());

        // A RUNNING two-stage plan serving stage 1 (the advance's world).
        plan = new StagedRolloutPlanEntity(
                release, org, UUID.randomUUID(), UUID.randomUUID(), StagedRolloutPlanEntity.Mode.CHECKPOINT, null);
        plan.serve(1);
        plan.move(StagedRolloutPlanEntity.Status.RUNNING);
        when(plans.findByReleaseIdAndOrganizationId(release, org)).thenReturn(Optional.of(plan));
        when(plans.findByReleaseId(release)).thenReturn(Optional.of(plan));
        StagedRolloutStageEntity first =
                new StagedRolloutStageEntity(plan.getId(), 1, StagedRolloutStageEntity.Kind.PERCENT, 50, 2);
        first.reach(java.time.Instant.now());
        StagedRolloutStageEntity second =
                new StagedRolloutStageEntity(plan.getId(), 2, StagedRolloutStageEntity.Kind.PERCENT, 100, 4);
        when(stageRows.findByPlanIdOrderByPositionAsc(plan.getId())).thenReturn(List.of(first, second));
        when(cohortRows.findByStageIdOrderByRankAsc(second.getId()))
                .thenReturn(List.of(
                        new StagedRolloutCohortEntity(second.getId(), UUID.randomUUID(), 0),
                        new StagedRolloutCohortEntity(second.getId(), UUID.randomUUID(), 1),
                        new StagedRolloutCohortEntity(second.getId(), UUID.randomUUID(), 2),
                        new StagedRolloutCohortEntity(second.getId(), UUID.randomUUID(), 3)));
        when(records.findByReleaseIdAndOrganizationIdOrderByChannelAsc(release, org))
                .thenReturn(List.of());
        when(releases.distributionTargetTenantIdsOf(release)).thenReturn(java.util.Set.of(UUID.randomUUID()));
    }

    @Test
    void anAdvanceThatLosesTheVersionRaceAnswersThePreciseConflict() {
        // The plan save loses the optimistic-stamp race to a concurrent
        // control: the precise 409, never a generic persistence fault.
        when(plans.save(any(StagedRolloutPlanEntity.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.control(org, release, UUID.randomUUID(), "ADVANCE", null))
                .isInstanceOf(DistributionException.class)
                .hasMessageContaining("changed concurrently");
    }

    @Test
    void anAdvanceOfACompletePlanIsThePreciseStateFault() {
        plan.move(StagedRolloutPlanEntity.Status.COMPLETE);

        assertThatThrownBy(() -> service.control(org, release, UUID.randomUUID(), "ADVANCE", null))
                .isInstanceOf(DistributionException.class)
                .hasMessageContaining("every stage reached");
    }
}

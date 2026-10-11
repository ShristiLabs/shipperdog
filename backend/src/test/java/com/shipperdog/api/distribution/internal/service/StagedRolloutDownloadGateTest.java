package com.shipperdog.api.distribution.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import com.shipperdog.api.distribution.internal.repository.StagedRolloutPlanRepository;
import com.shipperdog.api.tester.TenantTesterMembershipAccess;
import com.shipperdog.api.tester.TesterAccess;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The staged rollout's DIRECT arm — the download gate's own proofs
 * (issue #66, S13-06, AC3): a RUNNING plan serves its downloads to
 * the reached cohort only; every other standing and every
 * non-cohort-audience ask passes untouched (the gate narrows granted
 * downloads, it never widens or authorizes).
 */
class StagedRolloutDownloadGateTest {

    private StagedRolloutPlanRepository plans;
    private StagedRolloutReader reader;
    private TesterAccess testers;
    private TenantTesterMembershipAccess memberships;
    private StagedRolloutDownloadGate gate;

    private final UUID version = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID tester = UUID.randomUUID();
    private final UUID reachedTenant = UUID.randomUUID();
    private final UUID unreachedTenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        plans = mock(StagedRolloutPlanRepository.class);
        reader = mock(StagedRolloutReader.class);
        testers = mock(TesterAccess.class);
        memberships = mock(TenantTesterMembershipAccess.class);
        gate = new StagedRolloutDownloadGate(plans, reader, testers, memberships);
        when(testers.testerIdOfUser(user)).thenReturn(Optional.of(tester));
        when(memberships.tenantIdsOfTester(tester)).thenReturn(List.of(reachedTenant, unreachedTenant));
    }

    private StagedRolloutPlanEntity runningPlan() {
        StagedRolloutPlanEntity plan = new StagedRolloutPlanEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                version,
                StagedRolloutPlanEntity.Mode.CHECKPOINT,
                null);
        plan.serve(1);
        plan.move(StagedRolloutPlanEntity.Status.RUNNING);
        return plan;
    }

    @Test
    void aRunningPlanBlocksTheTesterOutsideTheReachedCohort() {
        StagedRolloutPlanEntity plan = runningPlan();
        when(plans.findByVersionId(version)).thenReturn(List.of(plan));
        when(reader.reachedTenantIdsOf(plan.getId(), 1)).thenReturn(Set.of(reachedTenant));
        when(memberships.tenantIdsOfTester(tester)).thenReturn(List.of(unreachedTenant));

        assertThat(gate.blocksDownload(version, user)).isTrue();
    }

    @Test
    void aTesterOfAReachedTenantPassesUntouched() {
        StagedRolloutPlanEntity plan = runningPlan();
        when(plans.findByVersionId(version)).thenReturn(List.of(plan));
        when(reader.reachedTenantIdsOf(plan.getId(), 1)).thenReturn(Set.of(reachedTenant));

        assertThat(gate.blocksDownload(version, user)).isFalse();
    }

    @Test
    void everyOtherPlanStandingNarrowsNothing() {
        for (StagedRolloutPlanEntity.Status status : List.of(
                StagedRolloutPlanEntity.Status.PLANNED,
                StagedRolloutPlanEntity.Status.COMPLETE,
                StagedRolloutPlanEntity.Status.ROLLED_BACK)) {
            StagedRolloutPlanEntity other = new StagedRolloutPlanEntity(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    version,
                    StagedRolloutPlanEntity.Mode.AUTOMATIC,
                    null);
            other.move(status);
            when(plans.findByVersionId(version)).thenReturn(List.of(other));
            assertThat(gate.blocksDownload(version, user))
                    .as("status %s narrows nothing", status)
                    .isFalse();
        }
    }

    @Test
    void noPlanAndNoTesterPersonaPassUntouched() {
        when(plans.findByVersionId(version)).thenReturn(List.of());
        assertThat(gate.blocksDownload(version, user)).isFalse();

        when(plans.findByVersionId(version)).thenReturn(List.of(runningPlan()));
        when(testers.testerIdOfUser(user)).thenReturn(Optional.empty());
        assertThat(gate.blocksDownload(version, user)).isFalse();
    }

    @Test
    void aMembershiplessTesterSitsInNoCohortAndIsBlocked() {
        StagedRolloutPlanEntity plan = runningPlan();
        when(plans.findByVersionId(version)).thenReturn(List.of(plan));
        when(reader.reachedTenantIdsOf(plan.getId(), 1)).thenReturn(Set.of(reachedTenant));
        when(memberships.tenantIdsOfTester(tester)).thenReturn(List.of());

        assertThat(gate.blocksDownload(version, user)).isTrue();
    }

    @Test
    void anUnavailableSourceAnswersNotBlockingNeverAThrow() {
        when(plans.findByVersionId(version)).thenThrow(new RuntimeException("store unavailable"));

        assertThat(gate.blocksDownload(version, user)).isFalse();
    }
}

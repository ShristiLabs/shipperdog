package com.shipperdog.api.distribution.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import com.shipperdog.api.distribution.internal.entity.StagedRolloutPlanEntity;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The staged rollout advancement pass's own proofs (issue #66, S13-06,
 * AC2 — the reconciliation-pass precedent): the disabled pass touches
 * nothing, the enabled pass tries every candidate once, and a
 * candidate's refusal (the gate, the dispatch-failure ownership rule)
 * is recorded and never the pass's.
 */
class StagedRolloutAdvancementSchedulerTest {

    private StagedTenantRolloutService stagedRollouts;
    private StagedRolloutAdvancementScheduler scheduler;

    private StagedRolloutAdvancementScheduler schedulerOf(boolean enabled) {
        stagedRollouts = mock(StagedTenantRolloutService.class);
        DistributionProperties properties = new DistributionProperties(
                java.util.Map.of(),
                new DistributionProperties.AppleConnector(
                        "https://api.appstoreconnect.apple.com", Duration.ofSeconds(20), Duration.ofSeconds(20)),
                new DistributionProperties.GooglePlayConnector(
                        "https://androidpublisher.googleapis.com/androidpublisher/v3/applications",
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(60)),
                new DistributionProperties.Reconciliation(false, Duration.ofMinutes(15)),
                new DistributionProperties.StagedRollout(
                        new DistributionProperties.StagedRollout.Advancement(enabled, Duration.ofMinutes(5))));
        return new StagedRolloutAdvancementScheduler(stagedRollouts, properties);
    }

    @Test
    void aDisabledPassNeverTouchesThePlans() {
        scheduler = schedulerOf(false);
        scheduler.advanceAutomaticPlans();
        verify(stagedRollouts, never()).advanceablePlans();
        verify(stagedRollouts, never()).tryAdvance(any());
    }

    @Test
    void anEnabledPassTriesEveryAdvanceablePlanOnce() {
        scheduler = schedulerOf(true);
        StagedRolloutPlanEntity first = new StagedRolloutPlanEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                StagedRolloutPlanEntity.Mode.AUTOMATIC,
                null);
        StagedRolloutPlanEntity second = new StagedRolloutPlanEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                StagedRolloutPlanEntity.Mode.AUTOMATIC,
                null);
        when(stagedRollouts.advanceablePlans()).thenReturn(List.of(first, second));

        scheduler.advanceAutomaticPlans();

        verify(stagedRollouts).tryAdvance(first);
        verify(stagedRollouts).tryAdvance(second);
    }

    @Test
    void aRefusedAdvancementIsNeverThePassFault() {
        scheduler = schedulerOf(true);
        // tryAdvance swallows the refusal itself (recorded on the plan's
        // own timeline); the pass never sees it. The belt: an escapee
        // fault from the candidates stream logs and waits for the next
        // tick, never killing the pass uncaught.
        when(stagedRollouts.advanceablePlans())
                .thenThrow(new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_BLOCKED, "gate refuses"));
        Mockito.doThrow(new DistributionException(
                        DistributionException.Code.DISTRIBUTION_STAGED_ROLLOUT_BLOCKED, "gate refuses"))
                .when(stagedRollouts)
                .tryAdvance(Mockito.any());

        scheduler.advanceAutomaticPlans();
        // Uncaught propagation would have failed the test itself.
    }
}

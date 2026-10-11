package com.shipperdog.api.distribution.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The deterministic cohort selection's own proofs (issue #66, S13-06,
 * AC1 — the story's technical note): stability across retries (the
 * pure function), the prefix property that makes percent stages
 * cumulative, the ceil arithmetic, and the widening rule's precise
 * refusals.
 */
class StagedRolloutCohortSelectionTest {

    @Test
    void theSeededOrderIsStableAcrossReevaluationAndIndependentOfInsertionOrder() {
        UUID release = UUID.randomUUID();
        List<UUID> targets = randomTenants(9);
        List<StagedRolloutCohortSelection.RankedTenant> first =
                StagedRolloutCohortSelection.seededOrder(release, targets);
        // Re-evaluated (a retry, a re-authoring of the same shapes).
        List<StagedRolloutCohortSelection.RankedTenant> second =
                StagedRolloutCohortSelection.seededOrder(release, targets);
        assertThat(second).containsExactlyElementsOf(first);
        // Insertion order is irrelevant (the digest is the order).
        List<UUID> shuffled = new ArrayList<>(targets);
        java.util.Collections.reverse(shuffled);
        assertThat(StagedRolloutCohortSelection.seededOrder(release, shuffled)).containsExactlyElementsOf(first);
        // Every target ranked exactly once, ranks are 0..n-1.
        assertThat(first.stream().map(StagedRolloutCohortSelection.RankedTenant::tenantId))
                .containsExactlyInAnyOrderElementsOf(targets);
        assertThat(first.stream()
                        .map(StagedRolloutCohortSelection.RankedTenant::rank)
                        .toList())
                .isEqualTo(IntStream.range(0, targets.size()).boxed().toList());
        // The seed is the RELEASE: a different release orders differently
        // (practically certain over nine tenants; the property the seed
        // exists for).
        assertThat(StagedRolloutCohortSelection.seededOrder(UUID.randomUUID(), targets))
                .isNotEqualTo(first);
    }

    @Test
    void percentStagesAreCumulativePrefixesOfTheOneOrder() {
        UUID release = UUID.randomUUID();
        List<UUID> targets = randomTenants(10);
        StagedRolloutCohortSelection.ResolvedCohorts resolved = StagedRolloutCohortSelection.resolve(
                release,
                targets,
                List.of(
                        new StagedRolloutCohortSelection.PercentStage(10),
                        new StagedRolloutCohortSelection.PercentStage(50),
                        new StagedRolloutCohortSelection.PercentStage(100)));
        Set<UUID> ten = tenantsOf(resolved, 0);
        Set<UUID> fifty = tenantsOf(resolved, 1);
        Set<UUID> hundred = tenantsOf(resolved, 2);
        // ceil arithmetic: 1, 5, 10 of ten.
        assertThat(ten).hasSize(1);
        assertThat(fifty).hasSize(5);
        assertThat(hundred).hasSize(10);
        // The prefix property: each cohort contains the previous.
        assertThat(fifty).containsAll(ten);
        assertThat(hundred).containsAll(fifty);
    }

    @Test
    void theCeilArithmeticRoundsUpSoEveryStageReachesSomeone() {
        UUID release = UUID.randomUUID();
        List<UUID> targets = randomTenants(7);
        StagedRolloutCohortSelection.ResolvedCohorts resolved = StagedRolloutCohortSelection.resolve(
                release,
                targets,
                List.of(
                        new StagedRolloutCohortSelection.PercentStage(1),
                        new StagedRolloutCohortSelection.PercentStage(50),
                        new StagedRolloutCohortSelection.PercentStage(99),
                        new StagedRolloutCohortSelection.PercentStage(100)));
        // ceil(0.07)=1, ceil(3.5)=4, ceil(6.93)=7, 7.
        assertThat(tenantsOf(resolved, 0)).hasSize(1);
        assertThat(tenantsOf(resolved, 1)).hasSize(4);
        assertThat(tenantsOf(resolved, 2)).hasSize(7);
        assertThat(tenantsOf(resolved, 3)).hasSize(7);
    }

    @Test
    void explicitStagesMustWidenAndNameOnlyTargets() {
        UUID release = UUID.randomUUID();
        List<UUID> targets = randomTenants(4);
        List<StagedRolloutCohortSelection.StageShape> widening = List.of(
                new StagedRolloutCohortSelection.ExplicitStage(List.of(targets.get(0), targets.get(1))),
                new StagedRolloutCohortSelection.ExplicitStage(targets));
        assertThat(StagedRolloutCohortSelection.resolve(release, targets, widening)
                        .stages())
                .hasSize(2);

        // Shrinking: the precise refusal.
        List<StagedRolloutCohortSelection.StageShape> shrinking = List.of(
                new StagedRolloutCohortSelection.ExplicitStage(targets),
                new StagedRolloutCohortSelection.ExplicitStage(List.of(targets.get(0))));
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(release, targets, shrinking))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only widens");

        // A non-target tenant: the precise refusal naming the stage.
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(
                        release,
                        targets,
                        List.of(new StagedRolloutCohortSelection.ExplicitStage(
                                List.of(targets.get(0), UUID.randomUUID())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not one of the release's target tenants");

        // A duplicate entry: the precise refusal.
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(
                        release,
                        targets,
                        List.of(new StagedRolloutCohortSelection.ExplicitStage(
                                List.of(targets.get(0), targets.get(0))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("named twice");
    }

    @Test
    void aPercentStageMayNotDropWhatAnExplicitStageReached() {
        UUID release = UUID.randomUUID();
        List<StagedRolloutCohortSelection.RankedTenant> order =
                StagedRolloutCohortSelection.seededOrder(release, randomTenants(10));
        // The LAST tenant of the seeded order: no percent stage under
        // 100 reaches it, so an explicit stage containing it can never
        // be followed by a smaller percent slice.
        UUID tail = order.get(order.size() - 1).tenantId();
        List<UUID> all = order.stream()
                .map(StagedRolloutCohortSelection.RankedTenant::tenantId)
                .collect(Collectors.toList());
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(
                        release,
                        all,
                        List.of(
                                new StagedRolloutCohortSelection.ExplicitStage(all),
                                new StagedRolloutCohortSelection.PercentStage(50))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("drops");
    }

    @Test
    void percentagesOutsideTheVocabularyAreRefused() {
        UUID release = UUID.randomUUID();
        List<UUID> targets = randomTenants(4);
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(
                        release, targets, List.of(new StagedRolloutCohortSelection.PercentStage(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> StagedRolloutCohortSelection.resolve(
                        release, targets, List.of(new StagedRolloutCohortSelection.PercentStage(101))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
    }

    private static Set<UUID> tenantsOf(StagedRolloutCohortSelection.ResolvedCohorts resolved, int stage) {
        Set<UUID> tenants = new LinkedHashSet<>();
        resolved.stages().get(stage).forEach(ranked -> tenants.add(ranked.tenantId()));
        return tenants;
    }

    private static List<UUID> randomTenants(int count) {
        return IntStream.range(0, count).mapToObj(ignored -> UUID.randomUUID()).toList();
    }
}

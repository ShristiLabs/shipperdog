package com.shipperdog.api.distribution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The distribution module's public surface for the release manifest's
 * staged-rollout block (issue #66, S13-06, AC6, PRD 16): the plan a
 * release freezes at its APPROVED crossing — the widening intent
 * (mode and ordered stages with their resolved cohorts) deep-copied as
 * values, exactly the store-submission precedent. The manifest is the
 * immutable copy of WHAT WAS PLANNED; the rollout's own append-only
 * ledger ({@code staged_rollout_events}, served by the staged rollout
 * view) records the reached stages through completion — the manifest
 * never rewrites history, so the two surfaces answer the freeze
 * question and the journey question each on its own.
 *
 * <p>Implementation lives in the module's {@code .internal.} packages;
 * cross-module callers must use only this interface (enforced by
 * ArchUnit).
 */
public interface StagedRolloutFacts {

    /**
     * The release's staged plan as the manifest freeze reads it.
     * Empty when the release carries no plan (the manifest's rollout
     * block keeps its honest nulls).
     */
    Optional<FrozenPlan> planOf(UUID releaseId);

    /**
     * The frozen plan: the advancement mode and the ordered stages
     * with their resolved cohorts (tenant ids as values).
     *
     * @param mode   the advancement mode (AUTOMATIC or CHECKPOINT)
     * @param stages the ordered stages, position-ascending
     */
    record FrozenPlan(String mode, List<FrozenStage> stages) {

        public FrozenPlan {
            stages = List.copyOf(stages);
        }
    }

    /**
     * One frozen stage.
     *
     * @param position   the stage's 1-based widening order
     * @param kind       PERCENT or EXPLICIT
     * @param percent    the percentage for PERCENT stages; null for EXPLICIT
     * @param cohort    the resolved cohort's tenant ids (the deterministic selection's output)
     */
    record FrozenStage(int position, String kind, Integer percent, List<UUID> cohort) {

        public FrozenStage {
            cohort = List.copyOf(cohort);
        }
    }
}

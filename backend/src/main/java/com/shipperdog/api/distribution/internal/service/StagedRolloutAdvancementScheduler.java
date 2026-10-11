package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.distribution.internal.config.DistributionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The staged rollout advancement pass's clock (issue #66, S13-06,
 * AC2): the unattended cadence of the AUTOMATIC mode — every RUNNING
 * AUTOMATIC plan with a stage to execute gets one advancement
 * attempt per pass, through the SAME engine the route drives (the
 * policy consult, the dispatch-failure ownership rule, the channel
 * arms). A refusal is recorded on the plan's own timeline (the
 * STAGE_BLOCKED row) and never the pass's — the gate's standing, not
 * a fault.
 *
 * <p><strong>Off by default</strong> (the reconciliation precedent,
 * #62): an unattended pass that widens production rollouts is a
 * deployment's own decision — {@code
 * shipperdog.distribution.staged-rollout.advancement.enabled=true}
 * ({@code DISTRIBUTION_STAGED_ROLLOUT__ADVANCEMENT__ENABLED} in the
 * deployment env). The ADVANCE action on the staged rollout route
 * serves the same engine everywhere regardless; this pass is the
 * automatic mode's unattended leg. CHECKPOINT plans are never
 * advanced here — a Release Manager's approval is a person's act.
 */
@Component
public class StagedRolloutAdvancementScheduler {

    private static final Logger log = LoggerFactory.getLogger(StagedRolloutAdvancementScheduler.class);

    /** The per-pass plan cap: one pass's blast radius against a slow store. */
    static final int PASS_LIMIT = 50;

    private final StagedTenantRolloutService stagedRollouts;
    private final DistributionProperties properties;

    public StagedRolloutAdvancementScheduler(
            StagedTenantRolloutService stagedRollouts, DistributionProperties properties) {
        this.stagedRollouts = stagedRollouts;
        this.properties = properties;
    }

    /** The unattended pass; the property guard is the opt-in. */
    @Scheduled(fixedDelayString = "${shipperdog.distribution.staged-rollout.advancement.fixed-delay:PT5M}")
    void advanceAutomaticPlans() {
        if (!properties.stagedRollout().advancement().enabled()) {
            return;
        }
        try {
            stagedRollouts.advanceablePlans().stream().limit(PASS_LIMIT).forEach(stagedRollouts::tryAdvance);
        } catch (RuntimeException e) {
            // The pass never dies of its candidates; a single
            // transaction-scoped fault logs and waits for the next tick.
            log.warn("the staged rollout advancement pass faulted: {}", e.getMessage());
        }
    }
}

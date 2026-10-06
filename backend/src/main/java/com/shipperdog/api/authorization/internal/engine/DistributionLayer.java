package com.shipperdog.api.authorization.internal.engine;

import com.shipperdog.api.authorization.AuthorizationAction;
import com.shipperdog.api.authorization.AuthorizationContext;
import com.shipperdog.api.authorization.AuthorizationDistributionPolicy;
import com.shipperdog.api.authorization.DenialReason;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * PRD 11/12's seventh and last chain member: <em>Is the distribution
 * active?</em> — whether any distribution state attached to the
 * artifact blocks the download. The layer consults every registered
 * {@link AuthorizationDistributionPolicy} contributor and denies when
 * any blocks; with no contributor registered it passes — nothing
 * restricts the artifact.
 *
 * <p>The M1 kernel registers no contributor (the {@code distribution}
 * table has no writer until the release epics), so the layer passes
 * every download today; M2's release model (E9/E13) registers the
 * real policy — window (start/end time), channel status, ring
 * targeting — against this exact seam, the same dependency inversion
 * the version module's {@code VersionDistributionContributor}
 * established. The layer runs for every DOWNLOAD decision and any
 * context carrying a distribution id, so the chain always shows its
 * verdict (PRD 12's example ends here); other actions skip it.
 */
@Component
class DistributionLayer implements AuthorizationLayer {

    private final ObjectProvider<AuthorizationDistributionPolicy> policies;

    DistributionLayer(ObjectProvider<AuthorizationDistributionPolicy> policies) {
        this.policies = policies;
    }

    @Override
    public int order() {
        return 700;
    }

    @Override
    public String name() {
        return "DISTRIBUTION";
    }

    @Override
    public boolean applicable(EvaluationState state, AuthorizationContext context) {
        return context.action() == AuthorizationAction.DOWNLOAD || context.distributionId() != null;
    }

    @Override
    public Verdict evaluate(EvaluationState state, AuthorizationContext context) {
        for (AuthorizationDistributionPolicy policy : policies.orderedStream().toList()) {
            if (policy.blocksDownload(context.artifactId(), context.versionId())) {
                return Verdict.failed(
                        DenialReason.DISTRIBUTION_BLOCKED,
                        "blocked by " + policy.getClass().getSimpleName());
            }
        }
        return Verdict.passed("no distribution policy blocks the download");
    }
}

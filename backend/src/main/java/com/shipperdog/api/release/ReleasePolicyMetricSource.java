package com.shipperdog.api.release;

import java.util.Optional;
import java.util.UUID;

/**
 * The analytics metric seam of the policy engine (issue #45, S10-06
 * AC4): the port through which a policy condition's {@link
 * PolicyMetric} references resolve — the release's CURRENT value for
 * the metric, answered by analytics at evaluation time, never stored
 * on the rule. The {@code GateMetricSource} shape (S10-03) applied to
 * the policy vocabulary: the gate module's seam binds its own two
 * metrics, this one binds PRD 33's wider list, and S11-04's analytics
 * feeds will register one implementation of each (the two seams share
 * the authority, never the vocabulary).
 *
 * <p><strong>Registrants answer the metrics they own.</strong> The
 * feedback module registers the first live source (S11-02, issue
 * #47): {@code OPEN_CRITICAL_BUGS} resolves as a real-time count over
 * the feedback table — a definitive value for every release, never
 * "no data". S11-04's analytics feeds land the rest (the crash and
 * engagement family) and may take the feedback metric over. Until a
 * metric has a source that can speak to it, it resolves empty and a
 * condition that needs it evaluates fail closed — the explicit
 * {@code NO_DATA} outcome with the missing metric named, exactly the
 * AC4 posture (a rule whose crash-free evidence cannot be produced
 * does not admit a promotion on the strength of missing data). An
 * empty answer means "no data for this release yet"; never a zero,
 * never a pass.
 */
public interface ReleasePolicyMetricSource {

    /**
     * The release's current value for the metric, or empty when
     * analytics holds no data for the release yet (the fail-closed
     * NO_DATA case). Implementations answer inside the caller's bound
     * scope.
     */
    Optional<Double> metricOf(UUID releaseId, PolicyMetric metric);
}

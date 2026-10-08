package com.shipperdog.api.gate;

import java.util.Optional;
import java.util.UUID;

/**
 * The analytics metric seam (issue #42, S10-03 AC4; the interface ONLY
 * — S11-04's dependency-cycle break): metric-backed gates
 * ({@code crash-free-threshold}, {@code open-critical-bugs}) resolve
 * their values from analytics AT EVALUATION TIME through this port,
 * never from manual submission — the PRD 26/27 model where feedback
 * and crash telemetry feed the release's quality state.
 *
 * <p><strong>No registrant ships in S10-03.</strong> The analytics
 * feeds land with S11-04, which will register exactly one
 * implementation of this interface (the {@code
 * ReleaseApprovalSource} single-registrant expectation: two metric
 * authorities would be a modeling error to surface at startup). Until
 * then evaluation resolves every metric-backed gate to {@code
 * NO_DATA} — <strong>fail-closed</strong>, the deliberate PRD 33
 * ethos this story documents: a release whose crash-free rate cannot
 * be evidenced does not cross into READY on the strength of missing
 * data. The dashboard renders NO_DATA distinctly from FAILED (no
 * data yet is not a bad result — but it blocks all the same until the
 * data arrives or the gate is overridden).
 *
 * <p>Implementations answer by release id and metric kind, inside the
 * caller's bound scope (the evaluation composes inside the VALIDATING
 * to READY crossing's transaction); an empty answer means "no data for
 * this release yet" and evaluates fail-closed — never a zero, never a
 * pass.
 */
public interface GateMetricSource {

    /**
     * The release's current value for the metric, or empty when
     * analytics holds no data for the release yet (the fail-closed
     * NO_DATA case).
     */
    Optional<Double> metricOf(UUID releaseId, GateCatalog.Metric metric);
}

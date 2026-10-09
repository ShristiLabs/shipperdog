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
 * <p><strong>One registrant answers the metrics it owns.</strong>
 * The feedback module registers the first live source (S11-02, issue
 * #47): {@code OPEN_CRITICAL_BUGS} resolves as a real-time count over
 * the feedback table — a definitive value for every release, never
 * "no data". {@code CRASH_FREE_RATE} stays unowned until S11-04's
 * analytics feeds register on the seam, and evaluation resolves every
 * unowned metric-backed gate to {@code NO_DATA} —
 * <strong>fail-closed</strong>, the deliberate PRD 33 ethos this
 * story documents: a release whose crash-free rate cannot be
 * evidenced does not cross into READY on the strength of missing
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
     * NO_DATA case). Values are in the gate surface's own unit —
     * percentages on the {@code NUMERIC_MIN} schema's validated 0..100
     * threshold scale, plain counts for {@code COUNT_MAX}.
     */
    Optional<Double> metricOf(UUID releaseId, GateCatalog.Metric metric);
}

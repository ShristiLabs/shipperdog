package com.shipperdog.api.release;

/**
 * The analytics metric vocabulary a policy condition may reference
 * (issue #45, S10-06, PRD 33): exactly the metrics the PRD's rule
 * examples name — the vulnerability, stability, and engagement
 * signals a standing rule blocks or holds on. Wider than the gate
 * catalog's two (S10-03's {@code GateCatalog.Metric} binds only what
 * a gate DEFINITION consumes), because a policy rule reasons about
 * the release's whole standing, not one gate's threshold.
 *
 * <p>Values resolve at evaluation time through {@link
 * ReleasePolicyMetricSource} — the analytics seam S11-04's feeds
 * register on. Until then every metric answers no data, and a rule
 * whose condition needs the metric evaluates FAIL CLOSED (AC4, the
 * S10-03 ethos): the outcome is the explicit {@code NO_DATA}, never
 * a silent zero and never a pass.
 */
public enum PolicyMetric {
    /** Critical known vulnerabilities across the release's artifacts (PRD 33: "critical vulnerabilities > 0"). */
    CRITICAL_VULNERABILITIES,
    /** The crash-free sessions rate, 0..1 (PRD 33: "crash-free < threshold"). */
    CRASH_FREE_RATE,
    /** The application-not-responding rate, 0..1 (PRD 33: "ANR rate > threshold"). */
    ANR_RATE,
    /** The uninstall rate, 0..1 (PRD 33: "uninstall rate > threshold"). */
    UNINSTALL_RATE,
    /** The install success rate, 0..1 (PRD 33: "install success rate < threshold"). */
    INSTALL_SUCCESS_RATE,
    /** The tester engagement rate, 0..1 (PRD 33: "tester engagement < threshold"). */
    TESTER_ENGAGEMENT,
    /** The tester approval rate, 0..1 (PRD 33's ALLOW example: "tester approval > threshold"). */
    TESTER_APPROVAL_RATE,
    /** Open critical bugs (PRD 33's ALLOW example: "open critical bugs = 0"). */
    OPEN_CRITICAL_BUGS
}

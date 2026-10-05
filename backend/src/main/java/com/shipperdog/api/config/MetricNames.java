package com.shipperdog.api.config;

/**
 * The {@code shipperdog_*} metric namespace (issue #7, acceptance
 * criterion 3) and the names reserved by PRD section 28. Metric names are
 * declared here in dotted Micrometer form; the Prometheus registry
 * renders them with underscores and, for durations, the Promethean
 * {@code _seconds} unit suffix (e.g.
 * {@code shipperdog_authorization_decision_duration_seconds}).
 *
 * <p>Reserved names and the story that will record them — stories MUST
 * take the names from this class so the namespace cannot drift:
 * <ul>
 *   <li>{@code shipperdog.artifact.upload.duration} — E5 artifact management
 *       ({@code artifact_upload_duration})</li>
 *   <li>{@code shipperdog.artifact.validation.duration} — E5
 *       ({@code artifact_validation_duration})</li>
 *   <li>{@code shipperdog.release.duration} — E9 release management
 *       ({@code release_duration})</li>
 *   <li>{@code shipperdog.distribution.duration} — E13 distribution
 *       ({@code distribution_duration})</li>
 *   <li>{@code shipperdog.download.count} — E5/E9 ({@code download_count})</li>
 *   <li>{@code shipperdog.installation.count} — E11 feedback/devices
 *       ({@code installation_count})</li>
 *   <li>{@code shipperdog.promotion.duration} — E10 promotion
 *       ({@code promotion_duration})</li>
 *   <li>{@code shipperdog.approval.duration} — E10 approvals
 *       ({@code approval_duration})</li>
 *   <li>{@link #AUTHORIZATION_DECISION_DURATION} — recorded since this
 *       issue by the RBAC enforcement point (SLO: under 100 ms, PRD
 *       section 42)</li>
 * </ul>
 */
public final class MetricNames {

    /** Timer of every RBAC permission decision, tagged {@code outcome=allow|deny}. */
    public static final String AUTHORIZATION_DECISION_DURATION = "shipperdog.authorization.decision.duration";

    private MetricNames() {}
}

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
 *       ({@code artifact_upload_duration}); recorded since issue #20
 *       (S5-01) as {@link #ARTIFACT_UPLOAD_DURATION}</li>
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

    /**
     * Timer of every completed artifact upload (issue #20, S5-01, the
     * reserved {@code artifact_upload_duration} of PRD 28), tagged
     * {@code path=multipart|resumable} (which of the two upload paths
     * carried the bytes) and {@code outcome=success|failure} — the
     * failure leg records a store or insert failure after streaming
     * began, not a rejected request.
     */
    public static final String ARTIFACT_UPLOAD_DURATION = "shipperdog.artifact.upload.duration";

    /**
     * Timer of every completed artifact metadata extraction (issue
     * #21, S5-02 — the PRD 42 SLO "artifact metadata processing
     * < 30 sec" observes THIS meter), tagged {@code platform=IOS|ANDROID}
     * and {@code outcome=success|failed-parse|failed-infra}: the
     * failed-parse leg is the malformed-binary outcome (artifact
     * FAILED), failed-infra the queue/storage outcome (artifact stays
     * VALIDATING).
     */
    public static final String ARTIFACT_EXTRACTION_DURATION = "shipperdog.artifact.extraction.duration";

    /**
     * Timer of every completed artifact validation attempt (issue #24,
     * S5-05 — the reserved {@code artifact_validation_duration} of PRD
     * 28), tagged {@code platform=IOS|ANDROID} and {@code
     * outcome=success|failed|blocked}: success moved the artifact
     * READY, failed moved it FAILED (a deterministic stage verdict),
     * and blocked left it VALIDATING (infrastructure refused — queue
     * full, storage unreadable after retries, or the extraction tier
     * unsettled). The PRD 42 "artifact metadata processing < 30 sec"
     * SLO observes the extraction meter plus THIS meter end to end.
     */
    public static final String ARTIFACT_VALIDATION_DURATION = "shipperdog.artifact.validation.duration";

    private MetricNames() {}
}

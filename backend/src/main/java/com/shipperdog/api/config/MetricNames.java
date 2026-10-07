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
 *       section 42); since S7-01 (issue #30) the layered authorization
 *       engine records the same meter tagged {@code engine=layered}
 *       (the RBAC leg carries no engine tag), so the SLO observes both
 *       engines as one metric family</li>
 * </ul>
 */
public final class MetricNames {

    /** Timer of every RBAC permission decision, tagged {@code outcome=allow|deny}. */
    public static final String AUTHORIZATION_DECISION_DURATION = "shipperdog.authorization.decision.duration";

    /**
     * Counter of every decision-input cache operation (issue #32,
     * S7-03), tagged {@code family=grants|membership|visibility|
     * tenants-of-tester} (which cached input) and
     * {@code outcome=hit|miss|degraded|bypass|invalidate} — the
     * cache-health signal beside the decision timer: {@code hit} and
     * {@code miss} are the ordinary cache-aside legs, {@code degraded}
     * counts Redis failures that fell through to direct evaluation
     * (fail-closed correctness, the story's AC3 — its sustained growth
     * is the operator's signal that decisions are running slower than
     * the SLO needs), {@code bypass} counts lookups while the cache is
     * disabled, and {@code invalidate} counts write-through key
     * drops. Invalidations that themselves fail (Redis down during a
     * mutation) count {@code degraded}; the TTL is the staleness
     * backstop for that window.
     */
    public static final String AUTHORIZATION_DECISION_CACHE = "shipperdog.authorization.decision.cache";

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

    /**
     * Timer of every download URL issuance (issue #38, S8-04 — the
     * PRD 42 SLO "download URL generation < 500 ms" observes THIS
     * meter's {@code outcome=issued} leg), tagged {@code
     * outcome=issued|denied}: issued is the ALLOW path that minted a
     * URL (decision, scoped read, routing, and minting), denied every
     * concealed denial — recorded for the ratio, excluded from the
     * SLO reading, which is about generation.
     */
    public static final String DOWNLOAD_URL_GENERATION_DURATION = "shipperdog.download.url.generation.duration";

    /**
     * Counter of every issued tester download (issue #38, S8-04 — the
     * reserved {@code download_count} of PRD 28, E5/E9), tagged
     * {@code platform=IOS|ANDROID}. Beside it, the durable per-
     * artifact aggregate on the artifact row (V23, the S11-04 input)
     * and the DOWNLOAD_ISSUED audit trail keep the number
     * reconcilable; this meter is the live operational signal.
     */
    public static final String DOWNLOAD_COUNT = "shipperdog.download.count";

    private MetricNames() {}
}

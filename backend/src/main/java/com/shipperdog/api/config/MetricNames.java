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
 *       ({@code approval_duration}); recorded since issue #41
 *       (S10-02) as {@link #APPROVAL_DURATION}</li>
 *   <li>{@link #AUTHORIZATION_DECISION_DURATION} — recorded since this
 *       issue by the RBAC enforcement point (SLO: under 100 ms, PRD
 *       section 42); since S7-01 (issue #30) the layered authorization
 *       engine records the same meter tagged {@code engine=layered}
 *       (the RBAC leg carries no engine tag), so the SLO observes both
 *       engines as one metric family</li>
 *   <li>{@link #ABAC_FAIL_CLOSED} — since S14-04 (issue #70): the
 *       ABAC engine's fail-closed counter (an unresolvable attribute
 *       or a storage-drifted condition answered with a denial, never
 *       a permit) — sustained growth is the operator's alert that
 *       policies reference attributes the decision contexts do not
 *       carry</li>
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
     * Counter of every ABAC evaluation that answered fail-closed on a
     * fault (S14-04, issue #70, AC6): tagged {@code cause=attribute|
     * condition} — an attribute the context could not resolve, or a
     * stored condition the engine could not parse. The denial is the
     * safe direction; this counter beside the ABAC_EVALUATION_FAULT
     * audit line is the alert.
     */
    public static final String ABAC_FAIL_CLOSED = "shipperdog.abac.fail-closed";

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

    /**
     * Counter of every request refused by the in-app rate limiter
     * (issue #39, S8-05; the {@code webhooks} rule since issue #54,
     * S12-04), tagged {@code rule=login|downloads|webhooks}: the
     * operator's signal that a client is being throttled — and, on the
     * login rule, the brute-force early-warning beside the identity
     * lockout. Refusals answer 429 {@code RATE_LIMITED} with a
     * {@code Retry-After} header; the meter is the aggregate view.
     */
    public static final String RATE_LIMIT_REJECTED = "shipperdog.ratelimit.rejected";

    /**
     * Timer of every channel-adapter call the dispatch sandbox made
     * (issue #61, S13-01, PRD 28 — store latency is a top debugging
     * question), tagged {@code channel}, {@code attempt}, and
     * {@code outcome} (ok, or the failure taxonomy's public code).
     * Served by the observation the sandbox opens per attempt, so the
     * timer and the trace span are the same artifact.
     */
    public static final String ADAPTER_CALL = "shipperdog.adapter.call.duration";

    /**
     * Counter of every provider rate-limit refusal a store connector
     * observed (issue #62, S13-02, AC6 — the quota telemetry beside
     * the sandbox's RATE_LIMITED backoff), tagged {@code channel}: a
     * connector's 429 family counts here the moment the provider
     * throttles it, whether the sandbox's retry lands the attempt or
     * the exhaustion fails the record — the operator's throttling
     * signal from the provider's side of the wire.
     */
    public static final String ADAPTER_RATE_LIMIT = "shipperdog.adapter.ratelimit";

    /**
     * Counter of every inbound webhook delivery a receiver answered
     * (issue #54, S12-04, FR-022), tagged {@code
     * provider=github|jenkins|gitlab|appstore|google-play} and
     * {@code outcome=accepted|ignored|duplicate|rejected|malformed|too_large}:
     * one increment per terminal answer, so the per-provider
     * accepted/rejected ratio, the duplicate rate (sender retry
     * health), and the rejection fingerprint are all observable from
     * one meter family. Rejections here are every authenticity failure
     * (the uniform 401/400 legs) and stale-timestamp replays;
     * rate-limited requests never reach a receiver and count under
     * {@link #RATE_LIMIT_REJECTED} {@code rule=webhooks} instead.
     */
    public static final String WEBHOOK_DELIVERIES = "shipperdog.webhooks.deliveries";

    /**
     * Timer of every approval round's terminal crossing (issue #41,
     * S10-02 — the reserved {@code approval_duration} of PRD 28, E10):
     * the span from a round's instantiation to its terminal status,
     * tagged {@code outcome=satisfied|rejected}. Rework is a NEW round
     * (each round times its own span), so the metric observes one
     * governance moment per recording — rework cycles read as multiple
     * recordings for one release.
     */
    public static final String APPROVAL_DURATION = "shipperdog.approval.duration";

    /**
     * Counter of every device check's terminal outcome (S16-07, issue
     * #169, ADR-0010 D9 — the check funnel's server-side split, since
     * a no-update check inserts no analytics row), tagged {@code
     * outcome=update_available|up_to_date|not_eligible|halted|
     * no_active_release|no_rollout|below_stage}: one increment per
     * answered check, the operator's live view of which refusal leg
     * (or the serving leg) a fleet sits behind.
     */
    public static final String OTA_CHECK_COUNT = "shipperdog.ota.check.count";

    /**
     * Counter of every landed OTA apply-failure report (S16-11, issue
     * #173, the OTA updates plan's workstream 7), tagged {@code
     * bundle} (the failing bundle's id) and {@code reason} (the
     * documented open row-value vocabulary of the {@code OTA_FAILED}
     * report: checksum_mismatch, apply_error, signature_invalid).
     * Counted on the rows that LANDED — the ingest's client-id dedup
     * filters retries before the increment, so an SDK's at-least-once
     * resends never double-count — beside the persisted OTA_FAILURES
     * rollup rows (the alert rules' evaluation input); this meter is
     * the live operational signal the moment a fleet starts failing
     * applies, before the hourly rollup lands.
     */
    public static final String OTA_APPLY_FAILURE = "shipperdog.ota.apply.failure";

    private MetricNames() {}
}

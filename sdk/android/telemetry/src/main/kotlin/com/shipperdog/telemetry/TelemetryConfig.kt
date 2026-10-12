package com.shipperdog.telemetry

import java.io.File

/**
 * The tester telemetry SDK's configuration (issue #72, S15-01): one
 * value object built from the telemetry block the portal download
 * issuance answers with — everything the SDK reports flows from it,
 * and nothing else identifies the tester (the token IS the identity;
 * there is no user id, email, or free-text field anywhere in the
 * payload — the privacy posture is structural).
 *
 * Construct this from the download answer's `telemetry` member:
 * `TelemetryConfig(baseUrl, token, tenantId, applicationId, releaseId)`
 * — the ids ride beside the token in that block so the integrator
 * never parses the (opaque) token itself.
 *
 * @param baseUrl the platform's API base (scheme + host, no path) —
 *     the ingest URL is derived: `{base}/api/v1/tenants/{tenantId}/analytics/events`.
 * @param token the telemetry token the download minted (the SDK's
 *     only credential; ingest-only by construction server-side).
 * @param tenantId the reporting tenant of the download.
 * @param applicationId the application the release belongs to (the
 *     feedback deep link's addressing).
 * @param releaseId the release the token reports for (every event the
 *     SDK emits names it — the server enforces the binding).
 * @param deviceId the OPTIONAL pairing of the token with one of the
 *     tenant's registered devices (the registry's own id — never a
 *     UDID or serial, which never leave the device module).
 * @param queueDirectory where the offline queue and the one-shot
 *     markers persist; null keeps everything in memory (sessions lost
 *     on process death — the file queue is the recommended shape, the
 *     Android adapter supplies it from the app's files dir).
 * @param maxQueueEvents the offline queue's bound: the oldest events
 *     are dropped first when the bound is hit (a bounded beacon,
 *     never an unbounded disk liability).
 */
data class TelemetryConfig(
    val baseUrl: String,
    val token: String,
    val tenantId: String,
    val applicationId: String,
    val releaseId: String,
    val deviceId: String? = null,
    val queueDirectory: File? = null,
    val maxQueueEvents: Int = DEFAULT_MAX_QUEUE_EVENTS,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl is required" }
        require(token.isNotBlank()) { "token is required" }
        require(tenantId.isNotBlank()) { "tenantId is required" }
        require(releaseId.isNotBlank()) { "releaseId is required" }
        require(maxQueueEvents > 0) { "maxQueueEvents must be positive" }
    }

    /** The frozen events contract's ingest route (docs/analytics.md). */
    val ingestUrl: String
        get() = baseUrl.trimEnd('/') + "/api/v1/tenants/" + tenantId + "/analytics/events"

    /** The tester portal's version deep link (the feedback handoff's destination). */
    fun portalVersionUrl(portalBaseUrl: String): String =
        portalBaseUrl.trimEnd('/') + "/applications/" + tenantId + "/" + applicationId

    companion object {
        const val DEFAULT_MAX_QUEUE_EVENTS: Int = 2_000
    }
}

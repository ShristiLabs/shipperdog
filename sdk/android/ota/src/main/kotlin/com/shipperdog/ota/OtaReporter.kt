package com.shipperdog.ota

import com.shipperdog.ota.OtaJson.StringEvent
import java.time.Clock
import java.util.UUID

/**
 * The OTA lifecycle reporter (issue #174, S16-12; ADR-0010 D4): the
 * five device lifecycle events of the frozen S16-08 ingest family —
 * OTA_SEEN, OTA_DOWNLOAD SUCCEEDED|FAILED, OTA_APPLIED
 * SUCCEEDED|FAILED, OTA_FAILED checksum_mismatch|apply_error|
 * signature_invalid, and OTA_ROLLED_BACK — riding the manifest's
 * `sdt1.` grant through the EXISTING batch ingest
 * (`POST /api/v1/tenants/{tenantId}/analytics/events`), the
 * telemetry beacon's own flush policy: batches of at most 500,
 * accepted (2xx) and permanently refused (4xx) batches removed,
 * transport failures and 5xx kept queued — at-least-once,
 * idempotent server-side by the client event id.
 *
 * The grant is BOUND by the check: [onManifest] is the bind (the
 * tenant, release, and bundle every subsequent event names); before
 * the first manifest there is nothing to report and no credential to
 * report it with, so enqueues are no-ops.
 */
internal class OtaReporter(
    private val config: OtaConfig,
    private val http: OtaHttp,
    private val clock: Clock,
) {

    private val queue = OtaEventQueue(config.queueDirectory, config.maxQueueEvents)

    @Volatile
    private var binding: Binding? = null

    /** The check's bind: the grant the manifest minted and the bundle its events name. */
    fun onManifest(manifest: OtaManifest) {
        binding = IngestGrant.parse(manifest.ingestToken)?.let { grant ->
            Binding(grant, manifest.bundleId, manifest.ingestToken)
        }
    }

    /** The seen report — one per served manifest (the funnel's seen stage). */
    fun seen() = enqueue(type = "OTA_SEEN", result = null)

    /** The download stage's two legs. */
    fun downloadSucceeded() = enqueue(type = "OTA_DOWNLOAD", result = "SUCCEEDED")

    fun downloadFailed() = enqueue(type = "OTA_DOWNLOAD", result = "FAILED")

    /** The apply stage's two legs (the app-supplied applier's own declared outcome). */
    fun applied() = enqueue(type = "OTA_APPLIED", result = "SUCCEEDED")

    fun applyRefused() = enqueue(type = "OTA_APPLIED", result = "FAILED")

    /** The failure family: the three-word reason vocabulary rides `result`, stored as given. */
    fun failedChecksumMismatch() = enqueue(type = "OTA_FAILED", result = "checksum_mismatch")

    fun failedSignatureInvalid() = enqueue(type = "OTA_FAILED", result = "signature_invalid")

    fun failedApplyError() = enqueue(type = "OTA_FAILED", result = "apply_error")

    /** The rollback report (the app's own post-apply verdict; resultless by shape). */
    fun rolledBack(detail: String? = null) = enqueue(type = "OTA_ROLLED_BACK", result = null, detail = detail)

    /**
     * Flushes the queue under the bound grant; answers the number of
     * accepted batches. One attempt per batch per call — the host's
     * next poll (or lifecycle moment) is the retry cadence.
     */
    fun flush(): Int {
        val bound = binding ?: return 0
        var accepted = 0
        while (true) {
            val next = queue.drain(MAX_BATCH_EVENTS)
            if (next.isEmpty()) {
                return accepted
            }
            val body = OtaJson.batch(next, MAX_BATCH_EVENTS)
            val status = try {
                http.post(bound.grant.ingestUrl(config.baseUrl), bound.token, body)
            } catch (transportFailure: Exception) {
                return accepted
            }
            when {
                status in 200..299 -> {
                    queue.removeOldest(next.size)
                    accepted++
                }
                status in 400..499 -> queue.removeOldest(next.size)
                else -> return accepted
            }
        }
    }

    /** The queue's depth (the host surfaces it in debug builds). */
    fun pendingEvents(): Int = queue.size()

    private fun enqueue(type: String, result: String?, detail: String? = null) {
        val bound = binding ?: return
        val payload = buildMap {
            if (config.deviceId.isNotBlank()) {
                put("device_id", config.deviceId)
            }
            if (!detail.isNullOrBlank()) {
                put("detail", detail.take(MAX_DETAIL_CHARS))
            }
        }
        val occurredAt = clock.instant().toString()
        queue.append(
            listOf(
                StringEvent(
                    OtaJson.event(
                        type = type,
                        releaseId = bound.grant.releaseId.toString(),
                        eventId = UUID.randomUUID().toString(),
                        occurredAtIso = occurredAt,
                        bundleId = bound.bundleId,
                        result = result,
                        payload = payload,
                    ),
                    occurredAt,
                ),
            ),
        )
    }

    private class Binding(val grant: IngestGrant, val bundleId: String, val token: String)

    companion object {
        /** The frozen contract's batch ceiling (docs/analytics.md). */
        const val MAX_BATCH_EVENTS: Int = 500

        /** The payload detail word's cap: a report member, not a log shipper. */
        const val MAX_DETAIL_CHARS: Int = 128
    }
}

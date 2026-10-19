package com.shipperdog.ota

import java.io.File

/**
 * The OTA updater SDK's configuration (issue #174, S16-12, ADR-0010
 * workstream 8): one value object carrying everything the check,
 * download, verify, and report funnel needs — the shipped app's
 * `sdc1.` app token (D3: the fleet's embedded identifier, minted by
 * the console/CLI), the device's own report vocabulary (the check
 * query's members, bounded exactly as the check surface validates
 * them), the embedded Ed25519 verification keys (D8: the public keys
 * copyable in the console, keyed by their 16-hex keyId), and the
 * server-cadence fallback used before the first manifest arrives.
 *
 * The poll loop is host-driven — the telemetry beacon's own doctrine:
 * no timers, no services, no background threads in the core. The
 * manifest's `pollIntervalSeconds` (D9: server-owned cadence) is the
 * loop's answer for when to check next; the host schedules the call
 * (the Android sample shows the complete Handler loop).
 *
 * @param baseUrl the platform's API base (scheme + host, no path) —
 *     the check URL and the lifecycle ingest URL derive from it.
 * @param appToken the application's `sdc1.` app token (the check's
 *     only credential; identifier, not secret — D3).
 * @param deviceId the device's own raw identifier (the rollout
 *     bucketing's input; at most 128 characters, S16-07's bound).
 * @param platform the device's platform tag (lowercased on the wire).
 * @param appVersion the app version it runs.
 * @param channel the OTA channel (the ring vocabulary).
 * @param bundleVersion the OPTIONAL bundle version label the device
 *     already carries — absent on a first check, present afterwards
 *     (D2: the up-to-date comparison's input).
 * @param pollIntervalSeconds the cadence fallback before the first
 *     manifest names the server's own.
 * @param verificationKeys the embedded public keys by keyId: values
 *     are Base64 of the RAW 32-byte Ed25519 public key (D8's frozen
 *     crypto contract; the keyId is 16 lowercase hex of the key's
 *     SHA-256 and is re-derived at verification as a pin).
 * @param queueDirectory where the offline lifecycle-event queue
 *     persists; null keeps events in memory.
 * @param downloadDirectory where bundle bytes stage (the `.part`
 *     resumable file and the verified bundle); null stages in a
 *     fresh temporary directory per download (the Android adapter
 *     supplies the app's own storage).
 * @param maxQueueEvents the offline queue's bound: oldest dropped
 *     first (the telemetry queue's own bounded-disk discipline).
 */
data class OtaConfig(
    val baseUrl: String,
    val appToken: String,
    val deviceId: String,
    val platform: String,
    val appVersion: String,
    val channel: String,
    val bundleVersion: String? = null,
    val pollIntervalSeconds: Long = DEFAULT_POLL_INTERVAL_SECONDS,
    val verificationKeys: Map<String, String> = emptyMap(),
    val queueDirectory: File? = null,
    val downloadDirectory: File? = null,
    val maxQueueEvents: Int = DEFAULT_MAX_QUEUE_EVENTS,
) {
    init {
        require(baseUrl.isNotBlank()) { "baseUrl is required" }
        require(appToken.startsWith(APP_TOKEN_PREFIX)) { "appToken must start with $APP_TOKEN_PREFIX" }
        require(deviceId.isNotBlank()) { "deviceId is required" }
        require(deviceId.length <= MAX_DEVICE_ID_CHARS) { "deviceId is at most $MAX_DEVICE_ID_CHARS characters" }
        require(platform.isNotBlank()) { "platform is required" }
        require(platform.length <= MAX_PLATFORM_CHARS) { "platform is at most $MAX_PLATFORM_CHARS characters" }
        require(appVersion.isNotBlank()) { "appVersion is required" }
        require(appVersion.length <= MAX_APP_VERSION_CHARS) { "appVersion is at most $MAX_APP_VERSION_CHARS characters" }
        require(channel.isNotBlank()) { "channel is required" }
        require(channel.length <= MAX_CHANNEL_CHARS) { "channel is at most $MAX_CHANNEL_CHARS characters" }
        if (bundleVersion != null) {
            require(bundleVersion.isNotBlank()) { "bundleVersion is blank or a label" }
            require(bundleVersion.length <= MAX_BUNDLE_VERSION_CHARS) {
                "bundleVersion is at most $MAX_BUNDLE_VERSION_CHARS characters"
            }
        }
        require(pollIntervalSeconds > 0) { "pollIntervalSeconds must be positive" }
        require(maxQueueEvents > 0) { "maxQueueEvents must be positive" }
        verificationKeys.forEach { (keyId, publicKeyBase64) ->
            require(keyId.length == KEY_ID_LENGTH) { "verification key id is $KEY_ID_LENGTH hex characters" }
            val decoded = java.util.Base64.getDecoder().decode(publicKeyBase64)
            require(decoded.size == ED25519_KEY_BYTES) { "verification key is Base64 of $ED25519_KEY_BYTES raw bytes" }
        }
    }

    /** The frozen check route (docs/openapi/baseline.yaml, GET /api/v1/ota/check). */
    val checkUrl: String
        get() = baseUrl.trimEnd('/') + "/api/v1/ota/check"

    /** The check query with every member the device reports, URL-encoded and normalized exactly as the check validates it. */
    internal fun checkQuery(): String = buildString {
        append("deviceId=").append(encode(deviceId.trim()))
        append("&platform=").append(encode(platform.trim().lowercase()))
        append("&appVersion=").append(encode(appVersion.trim()))
        if (!bundleVersion.isNullOrBlank()) {
            append("&bundleVersion=").append(encode(bundleVersion.trim()))
        }
        append("&channel=").append(encode(channel.trim().lowercase()))
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val APP_TOKEN_PREFIX: String = "sdc1."

        /** The check surface's own field bounds (S16-07), mirrored so config fails before the network does. */
        const val MAX_DEVICE_ID_CHARS: Int = 128
        const val MAX_PLATFORM_CHARS: Int = 64
        const val MAX_APP_VERSION_CHARS: Int = 64
        const val MAX_CHANNEL_CHARS: Int = 64
        const val MAX_BUNDLE_VERSION_CHARS: Int = 128

        /** D8's frozen key shapes. */
        const val ED25519_KEY_BYTES: Int = 32
        const val KEY_ID_LENGTH: Int = 16

        /** The cadence fallback until the first manifest names the server's own (D9). */
        const val DEFAULT_POLL_INTERVAL_SECONDS: Long = 3_600

        const val DEFAULT_MAX_QUEUE_EVENTS: Int = 200
    }
}

package com.shipperdog.ota

/**
 * The OTA manifest the check serves (issue #174, S16-12; the frozen
 * wire shape of docs/openapi/baseline.yaml's `Manifest` — camelCase
 * members, byte-for-byte the backend record): the bundle's identity
 * and content facts, the minted download URL (D5) and `sdt1.` ingest
 * grant (D4), the effective forced/minAppVersion overrides (D7), and
 * the server-owned poll cadence (D9).
 */
data class OtaManifest(
    val bundleId: String,
    val bundleVersion: String,
    val url: String,
    val sha256: String,
    val signature: String,
    val keyId: String,
    val sizeBytes: Long?,
    val forced: Boolean,
    val minAppVersion: String?,
    val ingestToken: String,
    val pollIntervalSeconds: Long,
) {
    init {
        require(bundleId.isNotBlank()) { "manifest bundleId is required" }
        require(bundleVersion.isNotBlank()) { "manifest bundleVersion is required" }
        require(url.isNotBlank()) { "manifest url is required" }
        require(sha256.isNotBlank()) { "manifest sha256 is required" }
        require(signature.isNotBlank()) { "manifest signature is required" }
        require(keyId.isNotBlank()) { "manifest keyId is required" }
        require(ingestToken.isNotBlank()) { "manifest ingestToken is required" }
        require(pollIntervalSeconds > 0) { "manifest pollIntervalSeconds must be positive" }
    }

    companion object {
        /** Parses one check response body; null members stay null, and an absent `forced` flag defaults to false. */
        fun parse(body: String): OtaManifest {
            val root = OtaJson.parseObject(body)
            return OtaManifest(
                bundleId = root.string("bundleId") ?: "",
                bundleVersion = root.string("bundleVersion") ?: "",
                url = root.string("url") ?: "",
                sha256 = root.string("sha256") ?: "",
                signature = root.string("signature") ?: "",
                keyId = root.string("keyId") ?: "",
                sizeBytes = root.number("sizeBytes")?.toLong(),
                forced = root.boolean("forced") ?: false,
                minAppVersion = root.string("minAppVersion"),
                ingestToken = root.string("ingestToken") ?: "",
                pollIntervalSeconds = root.number("pollIntervalSeconds")?.toLong() ?: 0L,
            )
        }
    }
}

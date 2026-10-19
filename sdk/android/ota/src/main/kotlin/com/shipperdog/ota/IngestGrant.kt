package com.shipperdog.ota

import java.util.Base64
import java.util.UUID

/**
 * The ingest grant carried by the manifest's `ingestToken` (issue
 * #174, S16-12; ADR-0010 D4): the check mints an `sdt1.` telemetry
 * token whose payload names the reporting tenant and the release the
 * lifecycle events must carry — the two identifiers the frozen
 * `Manifest` wire shape deliberately does not repeat, because the
 * grant IS their carrier.
 *
 * The token is HMAC-signed, not encrypted: the SDK reads the payload
 * blocks (integrity stays the server's to enforce — a tampered token
 * simply fails the ingest's own authentication) and never verifies or
 * mints anything. This is reading the grant the platform handed the
 * device, not parsing the customer's credentials; the payload is
 * `1|tenantId|testerUserId|releaseId|deviceId|expiryEpochSecond`,
 * each id a UUID, base64url-encoded — the exact blocks the backend
 * codec joins (the `TelemetryTokenCodec` doctrine).
 */
internal data class IngestGrant(val tenantId: UUID, val releaseId: UUID, val deviceId: UUID?) {

    /** The frozen ingest route (docs/analytics.md): `{base}/api/v1/tenants/{tenantId}/analytics/events`. */
    fun ingestUrl(baseUrl: String): String =
        baseUrl.trimEnd('/') + "/api/v1/tenants/" + tenantId + "/analytics/events"

    companion object {
        const val INGEST_TOKEN_PREFIX: String = "sdt1."
        const val PAYLOAD_VERSION: String = "1"
        const val PAYLOAD_FIELDS: Int = 6

        /** Reads the grant a manifest served; null when the token is not a well-formed `sdt1.` grant. */
        fun parse(token: String?): IngestGrant? {
            if (token == null || !token.startsWith(INGEST_TOKEN_PREFIX)) {
                return null
            }
            val body = token.substring(INGEST_TOKEN_PREFIX.length)
            val separator = body.indexOf('.')
            if (separator <= 0) {
                return null
            }
            val payload = try {
                String(Base64.getUrlDecoder().decode(body.substring(0, separator)), Charsets.UTF_8)
            } catch (malformed: IllegalArgumentException) {
                return null
            }
            val fields = payload.split('|')
            if (fields.size != PAYLOAD_FIELDS || fields[0] != PAYLOAD_VERSION) {
                return null
            }
            return try {
                IngestGrant(
                    tenantId = UUID.fromString(fields[1]),
                    releaseId = UUID.fromString(fields[3]),
                    deviceId = fields[4].ifBlank { null }?.let(UUID::fromString),
                )
            } catch (malformed: IllegalArgumentException) {
                null
            }
        }
    }
}

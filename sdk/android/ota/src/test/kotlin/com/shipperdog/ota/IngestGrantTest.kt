package com.shipperdog.ota

import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The ingest grant reader (issue #174, S16-12; ADR-0010 D4): the
 * `sdt1.` token the manifest mints carries the reporting tenant and
 * the release its lifecycle events name — the payload blocks the
 * backend codec joins. The reader tolerates everything the wire can
 * hand it and refuses nothing loudly: garbage is null (the reporter
 * runs unbound, the funnel never blocks on a bad grant).
 */
class IngestGrantTest {

    private val tenantId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val releaseId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val deviceId: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")

    private fun token(payload: String): String =
        "sdt1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".c2ln"

    @Test
    fun readsTheGrantBlocksTheBackendCodecJoins() {
        val grant = IngestGrant.parse(token("1|$tenantId|00000000-0000-0000-0000-000000000009|$releaseId|$deviceId|1893456000"))
        assertEquals(tenantId, grant!!.tenantId)
        assertEquals(releaseId, grant.releaseId)
        assertEquals(deviceId, grant.deviceId)
    }

    @Test
    fun aDevicelessGrantParsesWithNullDevice() {
        val grant = IngestGrant.parse(token("1|$tenantId|00000000-0000-0000-0000-000000000009|$releaseId||1893456000"))
        assertEquals(tenantId, grant!!.tenantId)
        assertEquals(releaseId, grant.releaseId)
        assertNull(grant.deviceId)
    }

    @Test
    fun theIngestUrlIsTheFrozenRouteUnderTheConfigBase() {
        val grant = IngestGrant.parse(token("1|$tenantId|00000000-0000-0000-0000-000000000009|$releaseId||1893456000"))
        assertEquals(
            "https://shipperdog.example.com/api/v1/tenants/$tenantId/analytics/events",
            grant!!.ingestUrl("https://shipperdog.example.com/"),
        )
    }

    @Test
    fun malformedGrantsAreNullNeverThrown() {
        assertNull(IngestGrant.parse(null))
        assertNull(IngestGrant.parse(""))
        assertNull(IngestGrant.parse("not-a-token"))
        assertNull(IngestGrant.parse("sdc1.other-prefix"))
        assertNull(IngestGrant.parse("sdt1.noblocks"))
        assertNull(IngestGrant.parse(token("2|$tenantId|t|$releaseId||1893456000")))
        assertNull(IngestGrant.parse(token("one|two|three")))
        assertNull(IngestGrant.parse(token("1|not-a-uuid|t|$releaseId||1893456000")))
        assertNull(IngestGrant.parse("sdt1.!!!not-base64!!!.x"))
    }
}

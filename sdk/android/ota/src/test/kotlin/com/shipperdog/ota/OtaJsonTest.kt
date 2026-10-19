package com.shipperdog.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON layer's behavior contract (issue #174, S16-12): the
 * manifest reader against the frozen baseline.yaml wire shape
 * (camelCase members, absent-null members, escapes), the lifecycle
 * event writer against the frozen S16-08 ingest shape (member order,
 * the optional result, the otaPayload object), and the batch body.
 */
class OtaJsonTest {

    @Test
    fun parsesTheFrozenManifestShapeWithEveryMember() {
        val manifest = OtaManifest.parse(
            """
            {"bundleId":"11111111-1111-1111-1111-111111111111","bundleVersion":"1.4.2",
             "url":"https://downloads.example.com/api/v1/downloads/dl-token",
             "sha256":"aabbcc0011223344aabbcc0011223344aabbcc0011223344aabbcc0011223344",
             "signature":"c2lnbmF0dXJl","keyId":"0123456789abcdef","sizeBytes":512,
             "forced":true,"minAppVersion":"2.0.0","ingestToken":"sdt1.grant",
             "pollIntervalSeconds":1800}
            """.trimIndent().replace("\n", ""),
        )
        assertEquals("11111111-1111-1111-1111-111111111111", manifest.bundleId)
        assertEquals("1.4.2", manifest.bundleVersion)
        assertEquals("https://downloads.example.com/api/v1/downloads/dl-token", manifest.url)
        assertEquals("aabbcc0011223344aabbcc0011223344aabbcc0011223344aabbcc0011223344", manifest.sha256)
        assertEquals("c2lnbmF0dXJl", manifest.signature)
        assertEquals("0123456789abcdef", manifest.keyId)
        assertEquals(512L, manifest.sizeBytes)
        assertEquals(true, manifest.forced)
        assertEquals("2.0.0", manifest.minAppVersion)
        assertEquals("sdt1.grant", manifest.ingestToken)
        assertEquals(1_800L, manifest.pollIntervalSeconds)
    }

    @Test
    fun absentOptionalMembersStayNullAndForcedDefaultsFalse() {
        val manifest = OtaManifest.parse(
            """
            {"bundleId":"b","bundleVersion":"1","url":"https://u","sha256":"h",
             "signature":"s","keyId":"k","ingestToken":"t","pollIntervalSeconds":60}
            """.trimIndent().replace("\n", ""),
        )
        assertNull(manifest.sizeBytes)
        assertNull(manifest.minAppVersion)
        assertEquals(false, manifest.forced)
    }

    @Test
    fun escapesDecodeInsideManifestStrings() {
        // The JSON carries three escapes: \" (quote), \\ (backslash), \u0041 ('A').
        val manifest = OtaManifest.parse(
            "{\"bundleId\":\"quote\\\"slash\\\\\\u0041\",\"bundleVersion\":\"1\"," +
                "\"url\":\"https://u\",\"sha256\":\"h\",\"signature\":\"s\",\"keyId\":\"k\"," +
                "\"ingestToken\":\"t\",\"pollIntervalSeconds\":60}",
        )
        assertEquals("quote\"slash\\A", manifest.bundleId)
    }

    @Test
    fun rejectsNonObjectAndTruncatedBodies() {
        assertFailsWith<IllegalArgumentException> { OtaManifest.parse("""["not","an","object"]""") }
        assertFailsWith<IllegalArgumentException> { OtaManifest.parse("""{"bundleId":"b"""") }
        assertFailsWith<IllegalArgumentException> { OtaManifest.parse("") }
    }

    @Test
    fun eventsCarryTheFrozenIngestShapeInMemberOrder() {
        val json = OtaJson.event(
            type = "OTA_DOWNLOAD",
            releaseId = "33333333-3333-3333-3333-333333333333",
            eventId = "44444444-4444-4444-4444-444444444444",
            occurredAtIso = "2026-10-17T12:00:00Z",
            bundleId = "11111111-1111-1111-1111-111111111111",
            result = "SUCCEEDED",
            payload = mapOf("device_id" to "device-1"),
        )
        assertEquals(
            "{\"type\":\"OTA_DOWNLOAD\",\"result\":\"SUCCEEDED\"," +
                "\"releaseId\":\"33333333-3333-3333-3333-333333333333\"," +
                "\"eventId\":\"44444444-4444-4444-4444-444444444444\"," +
                "\"occurredAt\":\"2026-10-17T12:00:00Z\"," +
                "\"otaBundleId\":\"11111111-1111-1111-1111-111111111111\"," +
                "\"otaPayload\":{\"device_id\":\"device-1\"}}",
            json,
        )
    }

    @Test
    fun resultlessTypesOmitTheMemberAndEscapingRidesValues() {
        val json = OtaJson.event(
            type = "OTA_ROLLED_BACK",
            releaseId = "r",
            eventId = "e",
            occurredAtIso = "2026-10-17T12:00:00Z",
            bundleId = "b",
        )
        assertTrue(json.startsWith("{\"type\":\"OTA_ROLLED_BACK\",\"releaseId\""))
        val escaped = OtaJson.event(
            type = "OTA_SEEN",
            releaseId = "r",
            eventId = "e\"id",
            occurredAtIso = "t",
            bundleId = "b",
        )
        assertTrue(escaped.contains("\"e\\\"id\""))
    }

    @Test
    fun batchesWrapAtMostTheLimit() {
        val events = (1..3).map {
            OtaJson.StringEvent("""{"n":$it}""", "2026-10-17T12:00:00Z")
        }
        assertEquals("""{"events":[{"n":1},{"n":2}]}""", OtaJson.batch(events, 2))
        assertEquals("""{"events":[{"n":1},{"n":2},{"n":3}]}""", OtaJson.batch(events, 5))
    }
}

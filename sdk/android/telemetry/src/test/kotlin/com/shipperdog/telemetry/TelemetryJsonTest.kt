package com.shipperdog.telemetry

import com.shipperdog.telemetry.TelemetryEvent.Result
import com.shipperdog.telemetry.TelemetryEvent.Type
import com.shipperdog.telemetry.TelemetryJson.StringEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wire contract of the SDK's JSON (issue #72, S15-01): the exact
 * field names and shapes of the frozen events contract
 * (docs/analytics.md) — camelCase names, the type's own members, the
 * client `eventId` on every event, and correct string escaping (the
 * payload vocabulary is closed, but a crash prefill or a future
 * member must never break the batch).
 */
class TelemetryJsonTest {

    private val config = TelemetryConfig(
        baseUrl = "https://shipperdog.example.com",
        token = "sdt1.fixture",
        tenantId = "11111111-1111-1111-1111-111111111111",
        applicationId = "22222222-2222-2222-2222-222222222222",
        releaseId = "33333333-3333-3333-3333-333333333333",
    )

    @Test
    fun installEventCarriesTheContractShape() {
        val json = TelemetryJson.event(
            config, TelemetryEvent(type = Type.INSTALL, result = Result.SUCCEEDED, eventId = "e-1"), "2026-10-12T12:00:00Z")
        assertEquals(
            "{\"type\":\"INSTALL\",\"result\":\"SUCCEEDED\",\"releaseId\":\"33333333-3333-3333-3333-333333333333\","
                + "\"eventId\":\"e-1\",\"occurredAt\":\"2026-10-12T12:00:00Z\"}",
            json,
        )
    }

    @Test
    fun sessionEventCarriesTheCountsAndDevice() {
        val withDevice = config.copy(deviceId = "44444444-4444-4444-4444-444444444444")
        val json = TelemetryJson.event(
            withDevice,
            TelemetryEvent(type = Type.SESSION, eventId = "e-2", sessions = 3, crashSessions = 1, anrSessions = 0),
            "2026-10-12T12:01:00Z",
        )
        assertTrue(json.contains("\"deviceId\":\"44444444-4444-4444-4444-444444444444\""))
        assertTrue(json.contains("\"sessions\":3"))
        assertTrue(json.contains("\"crashSessions\":1"))
        assertTrue(json.contains("\"anrSessions\":0"))
        assertFalse(json.contains("result"))
    }

    @Test
    fun stringsAreJsonEscaped() {
        val hostile = "quote\" backslash\\ newline\n tab\t ctrl\u0001"
        val json = TelemetryJson.event(config, TelemetryEvent(type = Type.INSTALL, result = Result.SUCCEEDED, eventId = hostile), "t")
        assertTrue(json.contains("\\\""))
        assertTrue(json.contains("\\\\"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\t"))
        assertTrue(json.contains("\\u0001"))
        assertFalse(json.contains('\n'))
        assertFalse(json.contains('\t'))
    }

    @Test
    fun batchWrapsEventsAndRespectsTheCeiling() {
        val events = (1..600).map { StringEvent("{\"n\":$it}", "t") }
        val full = TelemetryJson.batch(config, events, ShipperDog.MAX_BATCH_EVENTS)
        assertTrue(full.startsWith("{\"events\":["))
        assertTrue(full.endsWith("]}"))
        assertEquals(500, Regex("""\{"n":\d+\}""").findAll(full).count())
        val next = TelemetryJson.batch(config, events.drop(500), ShipperDog.MAX_BATCH_EVENTS)
        assertTrue(next.contains("{\"n\":501}"))
        assertTrue(next.contains("{\"n\":600}"))
        assertEquals(100, Regex("""\{"n":\d+\}""").findAll(next).count())
    }
}

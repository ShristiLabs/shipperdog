package com.shipperdog.telemetry

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The foreground counting (issue #72, S15-01): overlapping activities
 * open ONE session, the last one out closes it AND flushes, the
 * double-stop never closes twice, and a crash-flagged session
 * reports its crash. Observed through the real SDK's flush posts —
 * the gate has no separate truth of its own.
 */
class ForegroundGateTest {

    private val posts = mutableListOf<String>()

    private val transport = HttpTransport { _, _, body ->
        posts.add(body)
        200
    }

    private fun sdk(): ShipperDog = ShipperDog.forTesting(
        TelemetryConfig(
            baseUrl = "https://shipperdog.example.com",
            token = "sdt1.fixture",
            tenantId = "11111111-1111-1111-1111-111111111111",
            applicationId = "22222222-2222-2222-2222-222222222222",
            releaseId = "33333333-3333-3333-3333-333333333333",
            queueDirectory = Files.createTempDirectory("sd-gate").toFile(),
        ),
        transport,
        Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC),
    )

    @Test
    fun overlappingActivitiesOpenOneSessionAndTheLastOutFlushes() {
        val gate = ForegroundGate(sdk())
        gate.onActivityStarted()
        gate.onActivityStarted()
        gate.onActivityStopped()
        assertEquals(0, posts.size, "a session is still open")
        gate.onActivityStopped()
        assertEquals(1, posts.size, "the last one out closed and flushed")
        assertEquals(1, Regex("\"sessions\":1").findAll(posts[0]).count())
        assertEquals(1, Regex("\"crashSessions\":0").findAll(posts[0]).count())
    }

    @Test
    fun theDoubleStopNeverClosesTwice() {
        val gate = ForegroundGate(sdk())
        gate.onActivityStarted()
        gate.onActivityStopped()
        gate.onActivityStopped()
        gate.onActivityStopped()
        assertEquals(1, posts.size)
    }

    @Test
    fun aCrashFlaggedSessionReportsItsCrash() {
        val gate = ForegroundGate(sdk())
        gate.onActivityStarted()
        gate.markCrashed()
        gate.onActivityStopped()
        assertEquals(1, Regex("\"crashSessions\":1").findAll(posts[0]).count())
    }
}

package com.shipperdog.telemetry

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The beacon's behavior contract (issue #72, S15-01): the install
 * report's once-per-release discipline, the session summaries, the
 * crash marker's next-launch fold, the flush policy (accepted and
 * permanently-refused batches leave the queue; transport failures
 * and 5xx stay — at-least-once, idempotent server-side), the batch
 * ceiling across a deep queue, the opt-out's data hygiene, and the
 * feedback deep link's shape.
 */
class ShipperDogTest {

    private val directory: java.io.File = Files.createTempDirectory("sd-sdk").toFile()

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC)

    private class RecordingTransport(var statuses: MutableList<Int> = mutableListOf(200)) : HttpTransport {
        val posts = mutableListOf<Triple<String, String, String>>()

        override fun post(url: String, bearerToken: String, jsonBody: String): Int {
            posts.add(Triple(url, bearerToken, jsonBody))
            val status = if (statuses.isEmpty()) 200 else statuses.removeAt(0)
            if (status == -1) {
                throw java.io.IOException("transport failure fixture")
            }
            return status
        }
    }

    private fun config(deviceId: String? = null, dirKey: String = java.util.UUID.randomUUID().toString()) =
        TelemetryConfig(
        baseUrl = "https://shipperdog.example.com",
        token = "sdt1.fixture-token",
        tenantId = "11111111-1111-1111-1111-111111111111",
        applicationId = "22222222-2222-2222-2222-222222222222",
        releaseId = "33333333-3333-3333-3333-333333333333",
        deviceId = deviceId,
        queueDirectory = directory.resolve(dirKey),
    )

    @Test
    fun installReportsOncePerRelease() {
        val transport = RecordingTransport()
        val cfg = config()
        val sdk = ShipperDog.forTesting(cfg, transport, clock)
        sdk.onFirstLaunch()
        sdk.onFirstLaunch()
        sdk.flush()
        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts[0].third.contains("\"type\":\"INSTALL\""))
        // A fresh instance of the same release (same queue directory)
        // does not re-report: the marker is durable.
        val second = ShipperDog.forTesting(cfg, transport, clock)
        second.onFirstLaunch()
        second.flush()
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun sessionEndsReportSummariesAndFlushOnBackground() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        sdk.onSessionStart()
        sdk.onSessionEnd(crashed = false)
        sdk.flush()
        assertTrue(transport.posts[0].third.contains("\"sessions\":1"))
        assertTrue(transport.posts[0].third.contains("\"crashSessions\":0"))
    }

    @Test
    fun crashMarkersFoldIntoTheNextLaunchSummary() {
        val transport = RecordingTransport()
        val cfg = config()
        val sdk = ShipperDog.forTesting(cfg, transport, clock)
        sdk.recordCrashMarkerNow()
        assertTrue(sdk.hasPendingCrashMarker())
        val next = ShipperDog.forTesting(cfg, transport, clock)
        next.foldPendingCrashMarker()
        next.flush()
        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts[0].third.contains("\"crashSessions\":1"))
        assertEquals(false, next.hasPendingCrashMarker())
    }

    @Test
    fun flushRemovesAcceptedAndRefusedBatchesButKeepsRetriableOnes() {
        val accepted = RecordingTransport(mutableListOf(200))
        val sdkAccepted = ShipperDog.forTesting(config(), accepted, clock)
        sdkAccepted.onFirstLaunch()
        assertEquals(1, sdkAccepted.flush())
        assertEquals(0, sdkAccepted.pendingEvents())

        val refused = RecordingTransport(mutableListOf(400))
        val sdkRefused = ShipperDog.forTesting(config(), refused, clock)
        sdkRefused.onFirstLaunch()
        assertEquals(0, sdkRefused.flush())
        assertEquals(0, sdkRefused.pendingEvents())

        val serverHiccup = RecordingTransport(mutableListOf(503, -1))
        val sdkRetry = ShipperDog.forTesting(config(), serverHiccup, clock)
        sdkRetry.onFirstLaunch()
        assertEquals(0, sdkRetry.flush())
        assertEquals(1, sdkRetry.pendingEvents())
    }

    @Test
    fun aTransportFailureStopsTheFlushAndKeepsEverythingQueued() {
        val transport = RecordingTransport(mutableListOf(-1))
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        sdk.onFirstLaunch()
        sdk.onSessionStart()
        sdk.onSessionEnd()
        assertEquals(0, sdk.flush())
        assertEquals(2, sdk.pendingEvents())
    }

    @Test
    fun deepQueuesFlushInContractSizedBatches() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        repeat(1_201) {
            sdk.onSessionStart()
            sdk.onSessionEnd()
        }
        assertEquals(1_201, sdk.pendingEvents())
        assertEquals(3, sdk.flush())
        assertEquals(3, transport.posts.size)
        val batchSizes = transport.posts.map { post ->
            Regex("\"eventId\"").findAll(post.third).count()
        }
        assertEquals(listOf(500, 500, 201), batchSizes)
        assertEquals(0, sdk.pendingEvents())
    }

    @Test
    fun postsCarryTheIngestUrlAndBearerToken() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        sdk.onFirstLaunch()
        sdk.flush()
        assertEquals(
            "https://shipperdog.example.com/api/v1/tenants/11111111-1111-1111-1111-111111111111/analytics/events",
            transport.posts[0].first,
        )
        assertEquals("sdt1.fixture-token", transport.posts[0].second)
    }

    @Test
    fun everyEventCarriesAFreshDedupId() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        sdk.onFirstLaunch()
        sdk.onSessionStart()
        sdk.onSessionEnd()
        sdk.flush()
        val ids = Regex("\"eventId\":\"([^\"]+)\"").findAll(transport.posts[0].third).map { it.groupValues[1] }.toList()
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun thePairedDeviceRidesEveryEvent() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(deviceId = "44444444-4444-4444-4444-444444444444"), transport, clock)
        sdk.onFirstLaunch()
        sdk.flush()
        assertTrue(transport.posts[0].third.contains("\"deviceId\":\"44444444-4444-4444-4444-444444444444\""))
    }

    @Test
    fun optOutStopsReportingAndDropsHistory() {
        val transport = RecordingTransport()
        val sdk = ShipperDog.forTesting(config(), transport, clock)
        sdk.onFirstLaunch()
        sdk.onSessionStart()
        sdk.onSessionEnd()
        sdk.optOut()
        assertEquals(0, sdk.pendingEvents())
        sdk.onSessionStart()
        sdk.onSessionEnd()
        assertEquals(0, sdk.flush())
        assertEquals(0, transport.posts.size)
        // And the install marker is gone: a re-opt-in re-reports.
        val reOptIn = ShipperDog.forTesting(config(), transport, clock)
        reOptIn.onFirstLaunch()
        assertEquals(1, reOptIn.pendingEvents())
    }

    @Test
    fun theFeedbackDeepLinkCarriesTheReportTriggerAndACappedHint() {
        val sdk = ShipperDog.forTesting(config(), RecordingTransport(), clock)
        val plain = sdk.feedbackUrl("https://portal.example.com")
        assertEquals(
            "https://portal.example.com/applications/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222?report=1",
            plain,
        )
        val summary = "x".repeat(5_000)
        val capped = sdk.feedbackUrl("https://portal.example.com/", summary)
        assertTrue(capped.endsWith(java.net.URLEncoder.encode("x".repeat(ShipperDog.MAX_PREFILL_CHARS), Charsets.UTF_8)))
    }
}

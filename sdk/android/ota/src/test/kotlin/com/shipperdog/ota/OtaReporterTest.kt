package com.shipperdog.ota

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reporter's flush-policy legs the funnel tests do not reach
 * (issue #174, S16-12; the frozen S16-08 ingest contract): a
 * permanently-refused 4xx batch is dropped, never retried; batches
 * carry at most the contract's 500 events; and the rollback detail
 * word is capped at the report member's own bound.
 */
class OtaReporterTest {

    private val tenantId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val releaseId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private fun ingestToken(): String {
        val payload = "1|$tenantId|00000000-0000-0000-0000-000000000009|$releaseId||1893456000"
        return "sdt1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".bm9uY2U"
    }

    private fun manifest(): OtaManifest = OtaManifest(
        bundleId = "22222222-2222-2222-2222-222222222222",
        bundleVersion = "1.4.2",
        url = "https://downloads.example.com/api/v1/downloads/t",
        sha256 = "aabb",
        signature = "c2ln",
        keyId = "0123456789abcdef",
        sizeBytes = null,
        forced = false,
        minAppVersion = null,
        ingestToken = ingestToken(),
        pollIntervalSeconds = 60,
    )

    /** Records every posted body; answers a scriptable status. */
    private class PostHttp(var status: Int = 201) : OtaHttp {
        val bodies = mutableListOf<String>()

        override fun get(url: String, bearerToken: String) = OtaHttp.Response(204, null)

        override fun download(url: String, rangeFromBytes: Long?, target: java.io.File) = 200

        override fun post(url: String, bearerToken: String, jsonBody: String): Int {
            bodies.add(jsonBody)
            return status
        }
    }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)

    private fun reporter(http: PostHttp, maxQueueEvents: Int = 2_000): OtaReporter {
        val root = createTempDirectory("sd-ota-reporter").toFile()
        val config = OtaConfig(
            baseUrl = "https://shipperdog.example.com",
            appToken = "sdc1.fleet-token",
            deviceId = "device-reporter-1",
            platform = "Android",
            appVersion = "2.0.0",
            channel = "OTA",
            pollIntervalSeconds = 60,
            queueDirectory = root,
            maxQueueEvents = maxQueueEvents,
        )
        return OtaReporter(config, http, clock)
    }

    @Test
    fun aPermanentlyRefusedFourHundredBatchIsDroppedNeverRetried() {
        val http = PostHttp(status = 400)
        val reporter = reporter(http)
        reporter.onManifest(manifest())
        reporter.rolledBack()
        assertEquals(1, reporter.pendingEvents())
        assertEquals(0, reporter.flush())
        assertEquals(1, http.bodies.size)
        assertEquals(0, reporter.pendingEvents())
        // The refusal was permanent: with no new events, a later flush
        // posts nothing — the refused batch is gone, not retried.
        assertEquals(0, reporter.flush())
        assertEquals(1, http.bodies.size)
        // A fresh event is still attempted (here, refused again).
        reporter.rolledBack()
        assertEquals(0, reporter.flush())
        assertEquals(2, http.bodies.size)
        assertEquals(0, reporter.pendingEvents())
    }

    @Test
    fun batchesCarryAtMostTheFrozenFiveHundredEvents() {
        val http = PostHttp()
        val reporter = reporter(http)
        reporter.onManifest(manifest())
        repeat(1_001) { reporter.rolledBack() }
        assertEquals(3, reporter.flush())
        assertEquals(3, http.bodies.size)
        val counts = http.bodies.map { body -> Regex("\"type\":").findAll(body).count() }
        assertEquals(listOf(500, 500, 1), counts)
        assertEquals(0, reporter.pendingEvents())
    }

    @Test
    fun theRollbackDetailWordIsCappedAtItsOwnBound() {
        val http = PostHttp()
        val reporter = reporter(http)
        reporter.onManifest(manifest())
        reporter.rolledBack("x".repeat(300))
        reporter.flush()
        val body = http.bodies[0]
        assertTrue(body.contains("\"detail\":\"" + "x".repeat(OtaReporter.MAX_DETAIL_CHARS) + "\""))
        assertTrue(!body.contains("x".repeat(OtaReporter.MAX_DETAIL_CHARS + 1)))
    }

    @Test
    fun beforeTheFirstManifestThereIsNothingToReportAndNoCredentialToReportItWith() {
        val http = PostHttp()
        val reporter = reporter(http)
        reporter.rolledBack()
        assertEquals(0, reporter.pendingEvents())
        assertEquals(0, reporter.flush())
        assertEquals(0, http.bodies.size)
    }
}

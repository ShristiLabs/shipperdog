package com.shipperdog.ota

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
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
 * The updater's funnel contract (issue #174, S16-12): 204 is the
 * no-op standing with NOTHING reported; a served manifest reports
 * OTA_SEEN, walks download → verify → the app-supplied applier, and
 * reports every transition — the happy path's OTA_DOWNLOAD and
 * OTA_APPLIED SUCCEEDED legs, the refused applier's OTA_APPLIED
 * FAILED, the thrown applier's OTA_FAILED apply_error, and the
 * verifier's two failure words (checksum_mismatch,
 * signature_invalid). Events ride the manifest's `sdt1.` grant
 * through the existing ingest URL, batched `{"events":[...]}`,
 * deduped by fresh client event ids; the poll cadence follows the
 * manifest's server-owned seconds; the app-reported rollback lands
 * as the resultless OTA_ROLLED_BACK.
 */
class OtaUpdaterTest {

    private val tenantId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    private val releaseId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private val publicKeyBase64: String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded.copyOfRange(12, 44))

    private val keyId: String = OtaVerifier.keyIdOf(Base64.getDecoder().decode(publicKeyBase64))

    private val bundleBytes: ByteArray = ByteArray(256) { it.toByte() }

    private val checksumHex: String = OtaVerifier.hexOf(
        java.security.MessageDigest.getInstance("SHA-256").digest(bundleBytes),
    )

    private val signatureBase64: String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(checksumHex.toByteArray(Charsets.UTF_8))
                sign()
            },
        )

    /** A well-formed `sdt1.` grant, the backend codec's own payload blocks. */
    private fun ingestToken(): String {
        val payload = "1|$tenantId|00000000-0000-0000-0000-000000000009|$releaseId||1893456000"
        return "sdt1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".bm9uY2U"
    }

    private fun manifestJson(
        sha256: String = checksumHex,
        signature: String = signatureBase64,
        pollSeconds: Long = 900,
    ): String = (
        "{\"bundleId\":\"22222222-2222-2222-2222-222222222222\",\"bundleVersion\":\"1.4.2\"," +
            "\"url\":\"https://downloads.example.com/api/v1/downloads/t\",\"sha256\":\"$sha256\"," +
            "\"signature\":\"$signature\",\"keyId\":\"$keyId\",\"sizeBytes\":256,\"forced\":false," +
            "\"minAppVersion\":null,\"ingestToken\":\"" + ingestToken() + "\",\"pollIntervalSeconds\":$pollSeconds}"
        )

    /** The scriptable transport: the check answer, the bytes it serves, the post answers. */
    private class FakeHttp(
        var checkStatus: Int = 204,
        var checkBody: String? = null,
        val served: ByteArray = ByteArray(0),
        var downloadStatus: Int = 200,
        var defaultPostStatus: Int = 201,
    ) : OtaHttp {
        val checks = mutableListOf<Pair<String, String>>()

        val posts = mutableListOf<Triple<String, String, String>>()

        override fun get(url: String, bearerToken: String): OtaHttp.Response {
            checks.add(url to bearerToken)
            if (checkStatus == -1) {
                throw java.io.IOException("transport failure fixture")
            }
            return OtaHttp.Response(checkStatus, checkBody)
        }

        override fun download(url: String, rangeFromBytes: Long?, target: File): Int {
            if (downloadStatus in 200..299) {
                target.parentFile?.mkdirs()
                target.writeBytes(served)
            }
            return downloadStatus
        }

        override fun post(url: String, bearerToken: String, jsonBody: String): Int {
            posts.add(Triple(url, bearerToken, jsonBody))
            return defaultPostStatus
        }
    }

    private val clock: Clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC)

    private fun config(dirKey: String): OtaConfig {
        val root = createTempDirectory("sd-ota-updater").toFile()
        return OtaConfig(
            baseUrl = "https://shipperdog.example.com",
            appToken = "sdc1.fleet-token",
            deviceId = "device-funnel-1",
            platform = "Android",
            appVersion = "2.0.0",
            channel = "OTA",
            pollIntervalSeconds = 60,
            verificationKeys = mapOf(keyId to publicKeyBase64),
            queueDirectory = root.resolve(dirKey),
            downloadDirectory = root.resolve("$dirKey-bundles"),
        )
    }

    private fun updater(
        http: FakeHttp,
        config: OtaConfig = config(UUID.randomUUID().toString()),
    ): OtaUpdater {
        val verifier = OtaVerifier(config.verificationKeys)
        val reporter = OtaReporter(config, http, clock)
        return OtaUpdater.forTesting(config, http, OtaDownloader(http, config.downloadDirectory), verifier, reporter)
    }

    private fun typesPosted(http: FakeHttp): List<String> =
        http.posts.flatMap { Regex("\"type\":\"([A-Z_]+)\"").findAll(it.third).map { m -> m.groupValues[1] } }

    @Test
    fun theCheckPresentsTheSdc1TokenWithEveryQueryMemberLowercased() {
        val http = FakeHttp(checkStatus = 204)
        updater(http).checkNow()
        assertEquals(
            "https://shipperdog.example.com/api/v1/ota/check" +
                "?deviceId=device-funnel-1&platform=android&appVersion=2.0.0&channel=ota",
            http.checks[0].first,
        )
        assertEquals("sdc1.fleet-token", http.checks[0].second)
    }

    @Test
    fun a204IsTheNoOpStandingAndReportsNothing() {
        val http = FakeHttp(checkStatus = 204)
        val standing = updater(http).updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(OtaUpdater.OtaStanding.Kind.UP_TO_DATE, standing.kind)
        assertEquals(0, http.posts.size)
    }

    @Test
    fun theHappyPathWalksSeenDownloadVerifiedApplyAndReports() {
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(), served = bundleBytes)
        val standing = updater(http).updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(OtaUpdater.OtaStanding.Kind.APPLIED, standing.kind)
        assertEquals(listOf("OTA_SEEN", "OTA_DOWNLOAD", "OTA_APPLIED"), typesPosted(http))
        // The events rode the manifest's grant through the existing ingest URL.
        assertEquals(
            "https://shipperdog.example.com/api/v1/tenants/$tenantId/analytics/events",
            http.posts[0].first,
        )
        assertTrue(http.posts[0].second.startsWith("sdt1."))
        val body = http.posts[0].third
        assertTrue(body.startsWith("{\"events\":["))
        assertTrue(body.contains("\"releaseId\":\"$releaseId\""))
        assertTrue(body.contains("\"otaBundleId\":\"22222222-2222-2222-2222-222222222222\""))
        assertTrue(body.contains("\"device_id\":\"device-funnel-1\""))
        assertEquals(3, Regex("\"eventId\":\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toSet().size)
    }

    @Test
    fun thePollCadenceFollowsTheManifestsServerOwnedSeconds() {
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(pollSeconds = 3_600), served = bundleBytes)
        val sdk = updater(http)
        assertEquals(60L, sdk.nextPollSeconds())
        sdk.updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(3_600L, sdk.nextPollSeconds())
    }

    @Test
    fun aFailedDownloadReportsTheFailedLeg() {
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(), downloadStatus = 500)
        val standing = updater(http).updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(OtaUpdater.OtaStanding.Kind.DOWNLOAD_FAILED, standing.kind)
        assertEquals(listOf("OTA_SEEN", "OTA_DOWNLOAD"), typesPosted(http))
        assertTrue(http.posts[0].third.contains("\"type\":\"OTA_DOWNLOAD\",\"result\":\"FAILED\""))
    }

    @Test
    fun aChecksumMismatchReportsTheFrozenFailureWord() {
        val wrongChecksum = checksumHex.substring(0, 63) + if (checksumHex.endsWith("0")) "1" else "0"
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(sha256 = wrongChecksum), served = bundleBytes)
        val standing = updater(http).updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(OtaUpdater.OtaStanding.Kind.CHECKSUM_MISMATCH, standing.kind)
        assertEquals(listOf("OTA_SEEN", "OTA_DOWNLOAD", "OTA_FAILED"), typesPosted(http))
        assertTrue(http.posts[0].third.contains("\"type\":\"OTA_FAILED\",\"result\":\"checksum_mismatch\""))
    }

    @Test
    fun anInvalidSignatureReportsTheFrozenFailureWordAndNeverApplies() {
        val tampered = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(signatureBase64).also { it[10] = (it[10].toInt() xor 0x11).toByte() },
        )
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(signature = tampered), served = bundleBytes)
        var applied = false
        val standing = updater(http).updateNow { _, _ ->
            applied = true
            ApplyResult.Applied
        }
        assertEquals(OtaUpdater.OtaStanding.Kind.SIGNATURE_INVALID, standing.kind)
        assertEquals(false, applied)
        assertTrue(http.posts[0].third.contains("\"result\":\"signature_invalid\""))
    }

    @Test
    fun aRefusingApplierReportsAppliedFailedAndAThrownOneApplyError() {
        val refused = FakeHttp(checkStatus = 200, checkBody = manifestJson(), served = bundleBytes)
        val refusedStanding = updater(refused).updateNow({ _, _ -> ApplyResult.Refused })
        assertEquals(OtaUpdater.OtaStanding.Kind.APPLY_REFUSED, refusedStanding.kind)
        assertTrue(refused.posts[0].third.contains("\"type\":\"OTA_APPLIED\",\"result\":\"FAILED\""))

        val thrown = FakeHttp(checkStatus = 200, checkBody = manifestJson(), served = bundleBytes)
        val thrownStanding = updater(thrown).updateNow { _, _ -> throw IllegalStateException("apply broke") }
        assertEquals(OtaUpdater.OtaStanding.Kind.APPLY_ERROR, thrownStanding.kind)
        assertTrue(thrown.posts[0].third.contains("\"type\":\"OTA_FAILED\",\"result\":\"apply_error\""))
    }

    @Test
    fun theAppReportedRollbackIsTheResultlessRolledBackWord() {
        val http = FakeHttp(checkStatus = 200, checkBody = manifestJson(), served = bundleBytes)
        val sdk = updater(http)
        sdk.updateNow({ _, _ -> ApplyResult.Applied })
        sdk.reportRolledBack("crash_loop")
        assertEquals(2, http.posts.size)
        assertTrue(http.posts[1].third.contains("\"type\":\"OTA_ROLLED_BACK\""))
        assertTrue(!http.posts[1].third.contains("\"result\""))
        assertTrue(http.posts[1].third.contains("\"detail\":\"crash_loop\""))
    }

    @Test
    fun transportFailuresAnswerTheRetryStandingAndKeepNothingQueued() {
        val http = FakeHttp(checkStatus = -1)
        val sdk = updater(http)
        val standing = sdk.checkNow()
        assertEquals(OtaUpdater.OtaStanding.Kind.TRANSPORT_FAILURE, standing.kind)
        assertEquals(0, sdk.pendingEvents())
    }

    @Test
    fun unconfirmedBatchesStayQueuedUntilTheIngestAccepts() {
        val http = FakeHttp(
            checkStatus = 200,
            checkBody = manifestJson(),
            served = bundleBytes,
            defaultPostStatus = 503,
        )
        val sdk = updater(http)
        sdk.updateNow({ _, _ -> ApplyResult.Applied })
        assertEquals(3, sdk.pendingEvents())
        http.defaultPostStatus = 201
        assertEquals(1, sdk.flush())
        assertEquals(0, sdk.pendingEvents())
    }
}

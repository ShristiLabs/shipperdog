package com.shipperdog.ota

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The downloader's resumability contract (issue #174, S16-12): fresh
 * staging to `.part` then the atomic rename; resume of a partial
 * file via `Range: bytes=<staged>-` when the endpoint answers 206;
 * the GRACEFUL DEGRADE when it answers a plain 200 instead (the
 * compose proxy streams whole objects — restage from zero, never
 * fail); 416 on a fully staged file as completion; and the
 * size-mismatch refusal.
 */
class OtaDownloaderTest {

    private val directory: File = createTempDirectory("sd-ota-download").toFile()

    private val bytes: ByteArray = ByteArray(1_024) { it.toByte() }

    private val manifest: OtaManifest = OtaManifest(
        bundleId = "11111111-1111-1111-1111-111111111111",
        bundleVersion = "1.4.2",
        url = "https://downloads.example.com/api/v1/downloads/t",
        sha256 = OtaVerifier.hexOf(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),
        signature = "c2ln",
        keyId = "0123456789abcdef",
        sizeBytes = bytes.size.toLong(),
        forced = false,
        minAppVersion = null,
        ingestToken = "sdt1.grant",
        pollIntervalSeconds = 60,
    )

    /** Serves bytes with real range semantics, or the degraded plain-200 mode. */
    private class RangeHttp(
        private val bytes: ByteArray,
        private val mode: Mode = Mode.RANGE_206,
        private val status: Int = 200,
    ) : OtaHttp {
        enum class Mode { RANGE_206, PLAIN_200, ALWAYS_416, ERROR_500 }

        var lastRangeFrom: Long? = null

        override fun get(url: String, bearerToken: String) = OtaHttp.Response(204, null)

        override fun download(url: String, rangeFromBytes: Long?, target: File): Int {
            lastRangeFrom = rangeFromBytes
            if (status != 200) {
                return status
            }
            target.parentFile?.mkdirs()
            return when (mode) {
                Mode.RANGE_206 -> {
                    if (rangeFromBytes != null && rangeFromBytes > 0) {
                        java.io.FileOutputStream(target, true).use {
                            it.write(bytes, rangeFromBytes.toInt(), bytes.size - rangeFromBytes.toInt())
                        }
                        206
                    } else {
                        target.writeBytes(bytes)
                        200
                    }
                }
                Mode.PLAIN_200 -> {
                    target.writeBytes(bytes)
                    200
                }
                Mode.ALWAYS_416 -> 416
                Mode.ERROR_500 -> 500
            }
        }

        override fun post(url: String, bearerToken: String, jsonBody: String) = 201
    }

    @Test
    fun aFreshDownloadStagesAndSettlesAtomically() {
        val http = RangeHttp(bytes)
        val bundle = OtaDownloader(http, directory).download(manifest)
        assertEquals(bytes.size.toLong(), bundle!!.length())
        assertEquals(bytes.toList(), bundle.readBytes().toList())
        assertEquals(null, http.lastRangeFrom)
        assertTrue(bundle.name.endsWith(OtaDownloader.BUNDLE_SUFFIX))
        assertTrue(!File(directory, manifest.bundleId + OtaDownloader.PART_SUFFIX).exists())
    }

    @Test
    fun aPartialFileResumesWithARangeWhenTheEndpointAnswers206() {
        val http = RangeHttp(bytes)
        val downloader = OtaDownloader(http, directory)
        // A staged partial: the first 512 bytes of the bundle.
        val part = File(directory, manifest.bundleId + OtaDownloader.PART_SUFFIX)
        part.writeBytes(bytes.copyOfRange(0, 512))
        val bundle = downloader.download(manifest)
        assertEquals(bytes.size.toLong(), bundle!!.length())
        assertEquals(bytes.toList(), bundle.readBytes().toList())
        assertEquals(512L, http.lastRangeFrom)
    }

    @Test
    fun aPlain200AnswerDegradesGracefullyByRestagingFromZero() {
        val http = RangeHttp(bytes, mode = RangeHttp.Mode.PLAIN_200)
        val downloader = OtaDownloader(http, directory)
        val part = File(directory, manifest.bundleId + OtaDownloader.PART_SUFFIX)
        // A poisoned partial with WRONG leading bytes: the degrade must
        // restage from zero (the 200 answer carries the whole object),
        // never append onto it.
        part.writeBytes(ByteArray(512) { 0x7F })
        val bundle = downloader.download(manifest)
        assertEquals(bytes.toList(), bundle!!.readBytes().toList())
        assertEquals(512L, http.lastRangeFrom)
    }

    @Test
    fun a416OnACompletePartIsCompletion() {
        val http = RangeHttp(bytes, mode = RangeHttp.Mode.ALWAYS_416)
        val downloader = OtaDownloader(http, directory)
        val part = File(directory, manifest.bundleId + OtaDownloader.PART_SUFFIX)
        part.writeBytes(bytes)
        val bundle = downloader.download(manifest)
        assertEquals(bytes.toList(), bundle!!.readBytes().toList())
    }

    @Test
    fun aSizeMismatchAndAServerErrorBothRefuse() {
        val wrongSize = manifest.copy(sizeBytes = bytes.size + 5L)
        assertNull(OtaDownloader(RangeHttp(bytes), directory).download(wrongSize))
        assertNull(OtaDownloader(RangeHttp(bytes, mode = RangeHttp.Mode.ERROR_500, status = 500), directory).download(manifest))
    }

    @Test
    fun cleanupDropsTheStagedBytes() {
        val downloader = OtaDownloader(RangeHttp(bytes), directory)
        val bundle = downloader.download(manifest)!!
        downloader.cleanup(manifest)
        assertTrue(!bundle.exists())
        assertTrue(!File(directory, manifest.bundleId + OtaDownloader.PART_SUFFIX).exists())
    }
}

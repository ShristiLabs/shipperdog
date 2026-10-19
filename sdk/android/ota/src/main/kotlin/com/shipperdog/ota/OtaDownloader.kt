package com.shipperdog.ota

import java.io.File

/**
 * The resumable bundle downloader (issue #174, S16-12): stream-to-
 * file staging under the config's download directory — `<bundleId>.part`
 * while in flight, atomically renamed to `<bundleId>.bundle` once the
 * staged size matches the manifest's `sizeBytes` (when it names one).
 *
 * Resume is HTTP Range, opportunistically: a `.part` file present
 * asks the download URL for `bytes=<staged>-`. A 206 answer appends;
 * a plain 200 answer means the endpoint ignored the range (the
 * compose download proxy streams whole objects; production's signed
 * GCS URLs answer 206) — the downloader degrades gracefully by
 * restaging from zero rather than failing. A 416 with a fully staged
 * `.part` is completion, not failure.
 */
class OtaDownloader internal constructor(private val http: OtaHttp, private val directory: File?) {

    /** The staged, size-checked bundle file — or null when the download failed (the caller reports OTA_DOWNLOAD FAILED). */
    fun download(manifest: OtaManifest): File? {
        val staging = stagingDirectory()
        staging.mkdirs()
        val part = File(staging, manifest.bundleId + PART_SUFFIX)
        val staged = if (part.exists()) part.length() else 0L
        val status = try {
            http.download(manifest.url, if (staged > 0L) staged else null, part)
        } catch (transportFailure: Exception) {
            return null
        }
        return when {
            status == 206 || status == 200 -> settle(manifest, part)
            status == 416 && part.length() > 0L -> settle(manifest, part)
            else -> null
        }
    }

    private fun settle(manifest: OtaManifest, part: File): File? {
        if (manifest.sizeBytes != null && part.length() != manifest.sizeBytes) {
            return null
        }
        val bundle = File(stagingDirectory(), manifest.bundleId + BUNDLE_SUFFIX)
        if (!part.renameTo(bundle)) {
            bundle.delete()
            if (!part.renameTo(bundle)) {
                return null
            }
        }
        return bundle
    }

    /** Drops one bundle's staged bytes (the completed update's cleanup). */
    fun cleanup(manifest: OtaManifest) {
        File(stagingDirectory(), manifest.bundleId + PART_SUFFIX).delete()
        File(stagingDirectory(), manifest.bundleId + BUNDLE_SUFFIX).delete()
    }

    private fun stagingDirectory(): File = directory ?: fallback.apply { mkdirs() }

    private val fallback: File by lazy { createTempDirectory("sd-ota") }

    companion object {
        const val PART_SUFFIX: String = ".part"
        const val BUNDLE_SUFFIX: String = ".bundle"

        private fun createTempDirectory(prefix: String): File =
            java.nio.file.Files.createTempDirectory(prefix).toFile()
    }
}

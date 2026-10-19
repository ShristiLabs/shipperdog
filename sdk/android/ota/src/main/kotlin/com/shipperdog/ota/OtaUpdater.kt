package com.shipperdog.ota

import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The OTA updater (issue #174, S16-12; ADR-0010 workstream 8): the
 * complete device funnel — check, download, verify, apply, report —
 * driven by the host, the telemetry beacon's own doctrine (no
 * timers, no services, no background threads in the core).
 *
 * The check loop's shape: the host calls [checkNow] (or the full
 * [updateNow]) on ITS cadence — the sample wires Android's Handler
 * at [nextPollSeconds], the manifest's server-owned poll cadence
 * (D9), falling back to the config's until the first manifest.
 * Every check presents the `sdc1.` app token; 204 is the no-op
 * standing (up-to-date, not eligible, halted — indistinguishable on
 * the wire, D9); 200 is the manifest the funnel then walks.
 *
 * Every transition reports through the lifecycle queue: seen on the
 * served manifest, the download's two legs, the verifier's two
 * failure words, the applier's outcome, the thrown-apply
 * apply_error, and the app-reported rollback — flushed at the
 * cycle's end and on the host's next call (at-least-once, deduped
 * server-side by event id).
 *
 * Thread-safe: one update cycle at a time (a second concurrent
 * caller waits), so a poll and a foreground trigger cannot
 * double-download.
 */
class OtaUpdater private constructor(
    private val config: OtaConfig,
    private val http: OtaHttp,
    private val downloader: OtaDownloader,
    private val verifier: OtaVerifier,
    private val reporter: OtaReporter,
) {

    private val cycleLock = Any()

    private val pollSeconds = AtomicLong(config.pollIntervalSeconds)

    private val lastManifest = AtomicReference<OtaManifest?>(null)

    /** One check alone: the standing and the served manifest (transport failures answer TRANSPORT_FAILURE, never null). */
    fun checkNow(): OtaStanding {
        val response = try {
            http.get(config.checkUrl + "?" + config.checkQuery(), config.appToken)
        } catch (transportFailure: Exception) {
            return OtaStanding(OtaStanding.Kind.TRANSPORT_FAILURE, null)
        }
        return when {
            response.status == 204 -> {
                lastManifest.set(null)
                OtaStanding(OtaStanding.Kind.UP_TO_DATE, null)
            }
            response.status == 200 && response.body != null -> try {
                val manifest = OtaManifest.parse(response.body)
                pollSeconds.set(manifest.pollIntervalSeconds)
                lastManifest.set(manifest)
                reporter.onManifest(manifest)
                seenQuietly()
                OtaStanding(OtaStanding.Kind.UPDATE_AVAILABLE, manifest)
            } catch (malformed: IllegalArgumentException) {
                OtaStanding(OtaStanding.Kind.PROTOCOL_FAILURE, null)
            }
            else -> OtaStanding(OtaStanding.Kind.PROTOCOL_FAILURE, null)
        }
    }

    /**
     * One complete update cycle: check, download, verify, apply,
     * report, flush. The standing is the answer — every leg's
     * outcome rode the lifecycle queue on the way.
     */
    fun updateNow(applier: BundleApplier): OtaStanding = synchronized(cycleLock) {
        val standing = checkNow()
        val manifest = standing.manifest ?: return standing
        val bundle = downloader.download(manifest)
        if (bundle == null) {
            reporter.downloadFailed()
            flushQuietly()
            return OtaStanding(OtaStanding.Kind.DOWNLOAD_FAILED, manifest)
        }
        reporter.downloadSucceeded()
        when (val verification = verifier.verify(manifest, bundle)) {
            is OtaVerifier.VerificationResult.Verified -> Unit
            OtaVerifier.VerificationResult.ChecksumMismatch -> {
                reporter.failedChecksumMismatch()
                downloader.cleanup(manifest)
                flushQuietly()
                return OtaStanding(OtaStanding.Kind.CHECKSUM_MISMATCH, manifest)
            }
            OtaVerifier.VerificationResult.SignatureInvalid -> {
                reporter.failedSignatureInvalid()
                downloader.cleanup(manifest)
                flushQuietly()
                return OtaStanding(OtaStanding.Kind.SIGNATURE_INVALID, manifest)
            }
        }
        val applied = try {
            applier.apply(manifest, bundle)
        } catch (applyError: Exception) {
            reporter.failedApplyError()
            flushQuietly()
            return OtaStanding(OtaStanding.Kind.APPLY_ERROR, manifest)
        }
        if (applied == ApplyResult.Applied) {
            reporter.applied()
            downloader.cleanup(manifest)
            flushQuietly()
            OtaStanding(OtaStanding.Kind.APPLIED, manifest)
        } else {
            reporter.applyRefused()
            flushQuietly()
            OtaStanding(OtaStanding.Kind.APPLY_REFUSED, manifest)
        }
    }

    /** The app's post-apply rollback verdict (resultless by shape, reported through the bound grant). */
    fun reportRolledBack(detail: String? = null) {
        reporter.rolledBack(detail)
        flushQuietly()
    }

    /** The poll cadence: the last manifest's server-owned seconds, the config's fallback before one arrives. */
    fun nextPollSeconds(): Long = pollSeconds.get()

    /** The last served manifest (the host's own forced/minAppVersion handling). */
    fun currentManifest(): OtaManifest? = lastManifest.get()

    /** The queue's depth (the host surfaces it in debug builds). */
    fun pendingEvents(): Int = reporter.pendingEvents()

    /** Flushes the lifecycle queue; answers accepted batches (0 without a bound grant). */
    fun flush(): Int = reporter.flush()

    private fun flushQuietly() {
        try {
            reporter.flush()
        } catch (queueFailure: IllegalStateException) {
            // A flush that cannot persist drops nothing the poll's
            // next attempt will not re-derive; the cycle's answer
            // never depends on the report leg.
        }
    }

    private fun seenQuietly() {
        try {
            reporter.seen()
        } catch (reportFailure: Exception) {
            // The seen report's enqueue persists to the queue file; a
            // persist failure drops one report the next served
            // manifest re-mints — the check's standing never depends
            // on the report leg.
        }
    }

    /** One check cycle's terminal standing (the funnel's own vocabulary). */
    data class OtaStanding(val kind: Kind, val manifest: OtaManifest?) {

        enum class Kind {
            UP_TO_DATE,
            UPDATE_AVAILABLE,
            TRANSPORT_FAILURE,
            PROTOCOL_FAILURE,
            DOWNLOAD_FAILED,
            CHECKSUM_MISMATCH,
            SIGNATURE_INVALID,
            APPLY_ERROR,
            APPLY_REFUSED,
            APPLIED,
        }
    }

    companion object {

        /** Starts the updater: the SDK's one entry point. */
        fun start(config: OtaConfig): OtaUpdater {
            val http = UrlConnectionOtaHttp()
            return OtaUpdater(
                config = config,
                http = http,
                downloader = OtaDownloader(http, config.downloadDirectory),
                verifier = OtaVerifier(config.verificationKeys),
                reporter = OtaReporter(config, http, Clock.systemUTC()),
            )
        }

        /** The test seam: every collaborator injectable against fakes. */
        internal fun forTesting(
            config: OtaConfig,
            http: OtaHttp,
            downloader: OtaDownloader,
            verifier: OtaVerifier,
            reporter: OtaReporter,
        ): OtaUpdater = OtaUpdater(config, http, downloader, verifier, reporter)
    }
}

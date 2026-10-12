package com.shipperdog.telemetry

import com.shipperdog.telemetry.TelemetryJson.StringEvent
import java.io.File
import java.time.Clock

/**
 * The tester telemetry beacon (issue #72, S15-01, PRD 37): the small
 * library a test build embeds to report install confirmation,
 * session summaries, and crash context to ShipperDog's frozen events
 * contract. A beacon, not a framework: no timers, no services, no
 * background threads — the host's lifecycle moments (session end,
 * app background) drive the flushes, which is the zero-background-
 * battery posture the acceptance criteria pin (docs/telemetry-sdk.md).
 *
 * The crash contract: the uncaught-exception handler CHAINS the
 * previous handler (never swallows — the customer's own crash
 * reporting stays theirs, PRD 34's "not a Sentry replacement"), and
 * the crash itself is recorded as a durable marker the NEXT launch
 * folds into a session summary — nothing dials the network on a
 * crashing process.
 *
 * Identity is structural: the token the download minted is the only
 * credential and the only tester identity; the payload carries the
 * paired device id at most (a registry id, never a UDID/serial) —
 * no PII beyond that, and [optOut] drops the queue and stops
 * everything.
 */
class ShipperDog private constructor(
    private val config: TelemetryConfig,
    private val transport: HttpTransport,
    private val clock: Clock,
) {

    private val queue = EventQueue(config.queueDirectory, config.maxQueueEvents)

    private val markers: File?
        get() = config.queueDirectory?.resolve("markers")

    @Volatile
    private var optedOut: Boolean = false

    @Volatile
    private var sessionOpen: Boolean = false

    /** The first launch of the paired release: the INSTALL report, exactly once per release. */
    fun onFirstLaunch() {
        if (optedOut || installMarkerExists()) {
            return
        }
        writeInstallMarker()
        enqueue(TelemetryEvent.install())
    }

    /** A session begins (host lifecycle: foreground). */
    fun onSessionStart() {
        if (optedOut) {
            return
        }
        sessionOpen = true
    }

    /**
     * A session ends (host lifecycle: background or termination): the
     * accumulated summary — one session, crash-flagged when the crash
     * handler caught one inside it.
     */
    fun onSessionEnd(crashed: Boolean = false) {
        if (optedOut) {
            return
        }
        if (sessionOpen || crashed) {
            sessionOpen = false
            enqueue(TelemetryEvent.session(sessions = 1, crashes = if (crashed) 1 else 0))
        }
    }

    /**
     * The crash fold of the NEXT launch after a crash: the marker the
     * crash handler wrote becomes the session summary the queue
     * carries (one session, one crash) — call this once at start,
     * after [onFirstLaunch]; it is a no-op without a marker.
     */
    fun foldPendingCrashMarker() {
        if (optedOut) {
            return
        }
        val marker = crashMarker() ?: return
        if (!marker.exists()) {
            return
        }
        marker.delete()
        enqueue(TelemetryEvent.session(sessions = 1, crashes = 1))
    }

    /** True while a crash marker from the previous run awaits folding. */
    fun hasPendingCrashMarker(): Boolean = crashMarker()?.exists() == true

    /**
     * Flushes the offline queue: batches of at most
     * [TelemetryConfig]'s contract ceiling (500), one release per
     * batch, each POST authenticated by the token. Accepted (2xx) and
     * permanently refused (4xx — the batch's own shape is wrong, the
     * contract's field-keyed report names the offender) batches are
     * removed; transport failures and 5xx KEEP the batch queued —
     * at-least-once, idempotent server-side by the client event id.
     * One attempt per batch per call: the host's next lifecycle
     * moment is the retry cadence (no timers, no battery).
     *
     * @return the number of batches accepted (removed from the queue).
     */
    fun flush(): Int {
        if (optedOut) {
            return 0
        }
        var accepted = 0
        while (true) {
            val next = queue.drain(MAX_BATCH_EVENTS)
            if (next.isEmpty()) {
                return accepted
            }
            val body = TelemetryJson.batch(config, next, MAX_BATCH_EVENTS)
            val status = try {
                transport.post(config.ingestUrl, config.token, body)
            } catch (transportFailure: Exception) {
                return accepted
            }
            when {
                status in 200..299 -> {
                    queue.removeOldest(next.size)
                    accepted++
                }
                status in 400..499 -> queue.removeOldest(next.size)
                else -> return accepted
            }
        }
    }

    /** The tester's explicit opt-out: stop reporting, drop the queued history. */
    fun optOut() {
        optedOut = true
        queue.clear()
        installMarkerFile()?.delete()
        crashMarker()?.delete()
    }

    /**
     * The feedback handoff (PRD FR-027): the tester portal's version
     * deep link with the report trigger and an OPTIONAL crash summary
     * prefill (capped — a deep link is a URL, not a log shipper; the
     * full stack stays in the customer's own crash tooling, PRD 34).
     */
    fun feedbackUrl(portalBaseUrl: String, crashSummary: String? = null): String {
        val base = config.portalVersionUrl(portalBaseUrl) + "?report=1"
        if (crashSummary.isNullOrBlank()) {
            return base
        }
        val capped = crashSummary.take(MAX_PREFILL_CHARS).replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return base + "&hint=" + java.net.URLEncoder.encode(capped, Charsets.UTF_8)
    }

    /** The queue's depth (the host surfaces it in debug builds). */
    fun pendingEvents(): Int = queue.size()

    private fun enqueue(event: TelemetryEvent) {
        val occurredAt = clock.instant().toString()
        queue.append(listOf(StringEvent(TelemetryJson.event(config, event, occurredAt), occurredAt)))
    }

    private fun installMarkerExists(): Boolean = installMarkerFile()?.exists() == true

    private fun writeInstallMarker() {
        val file = installMarkerFile() ?: return
        file.parentFile?.mkdirs()
        file.writeText("sent")
    }

    private fun installMarkerFile(): File? = markers?.resolve("install-" + config.releaseId + ".sent")

    private fun crashMarker(): File? = markers?.resolve("crash-" + config.releaseId + ".marker")

    /** Writes the crash marker WITHOUT touching the queue (the crashing thread's only safe act). */
    internal fun recordCrashMarkerNow() {
        val file = crashMarker() ?: return
        file.parentFile?.mkdirs()
        file.writeText("crash")
    }

    companion object {
        /** The frozen contract's batch ceiling (docs/analytics.md). */
        const val MAX_BATCH_EVENTS: Int = 500

        /** The feedback prefill's cap: a URL parameter, not a log shipper. */
        const val MAX_PREFILL_CHARS: Int = 2_000

        /**
         * Starts the beacon: the SDK's one entry point. No Android
         * dependency here — the Android adapter wraps this with the
         * Application-lifecycle session tracking.
         */
        fun start(config: TelemetryConfig): ShipperDog =
            ShipperDog(config, UrlConnectionTransport(), Clock.systemUTC())

        /** The test seam: the flush policy suite drives the transport and clock. */
        internal fun forTesting(config: TelemetryConfig, transport: HttpTransport, clock: Clock): ShipperDog =
            ShipperDog(config, transport, clock)
    }
}

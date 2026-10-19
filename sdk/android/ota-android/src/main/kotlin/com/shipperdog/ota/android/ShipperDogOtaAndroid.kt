package com.shipperdog.ota.android

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.shipperdog.ota.BundleApplier
import com.shipperdog.ota.OtaConfig
import com.shipperdog.ota.OtaUpdater
import java.io.File

/**
 * The OTA updater's Android entry point (issue #174, S16-12): the
 * complete integration is one call in `Application.onCreate` plus
 * the app's own apply callback —
 *
 * ```kotlin
 * class DemoApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ota = ShipperDogOtaAndroid.start(this, OTA_CONFIG, applier = ::applyBundle)
 *     }
 * }
 * ```
 *
 * What the one call does: starts the updater with the app's own
 * storage (the lifecycle queue under the files dir, bundle staging
 * under the cache dir — staged bytes are re-downloadable, reports
 * are not) and returns it. The check loop rides a main-thread
 * [Handler] at the manifest's server-owned cadence
 * ([OtaUpdater.nextPollSeconds]); each cycle runs the funnel on a
 * background thread (the apply callback never blocks the main
 * thread), re-arms even when the funnel throws (only [stop] halts
 * the loop), and [stop] halts the loop when the host backgrounds or
 * opts out — the telemetry beacon's no-timers-when-idle posture
 * applied to the poll the server owns.
 */
object ShipperDogOtaAndroid {

    private var handler: Handler? = null

    /** Starts the updater and the poll loop; the applier is the app's own bundle-swap act. */
    fun start(app: Application, config: OtaConfig, applier: BundleApplier): OtaUpdater {
        stop()
        val queueDirectory = File(app.filesDir, "shipperdog-ota")
        val downloadDirectory = File(app.cacheDir, "shipperdog-ota-bundles")
        val updater = OtaUpdater.start(
            OtaConfig(
                baseUrl = config.baseUrl,
                appToken = config.appToken,
                deviceId = config.deviceId,
                platform = config.platform,
                appVersion = config.appVersion,
                channel = config.channel,
                bundleVersion = config.bundleVersion,
                pollIntervalSeconds = config.pollIntervalSeconds,
                verificationKeys = config.verificationKeys,
                queueDirectory = queueDirectory,
                downloadDirectory = downloadDirectory,
                maxQueueEvents = config.maxQueueEvents,
            ),
        )
        scheduleNext(updater, applier)
        return updater
    }

    /** Stops the poll loop (the host backgrounds, or opts out of updates entirely). */
    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler = null
    }

    private fun scheduleNext(updater: OtaUpdater, applier: BundleApplier) {
        val handler = Handler(Looper.getMainLooper())
        this.handler = handler
        val cycle = object : Runnable {
            override fun run() {
                Thread {
                    try {
                        updater.updateNow(applier)
                    } catch (cycleFailure: Exception) {
                        // A thrown funnel leg never stops the poll the
                        // server owns; the standing is the funnel's
                        // answer, not this thread's exception.
                    } finally {
                        handler.postDelayed(this, updater.nextPollSeconds() * 1_000)
                    }
                }.start()
            }
        }
        handler.postDelayed(cycle, 0)
    }
}

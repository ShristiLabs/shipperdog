package com.shipperdog.telemetry.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import com.shipperdog.telemetry.CrashHandler
import com.shipperdog.telemetry.ForegroundGate
import com.shipperdog.telemetry.ShipperDog
import com.shipperdog.telemetry.TelemetryConfig
import java.io.File

/**
 * The SDK's Android entry point (issue #72, S15-01, AC1): the
 * complete integration is one call in `Application.onCreate` —
 *
 * ```kotlin
 * class DemoApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         ShipperDogAndroid.start(this, TELEMETRY_CONFIG)   // 1 line + the config value
 *     }
 * }
 * ```
 *
 * What the one call does: starts the beacon with the app's private
 * queue directory (the offline queue and the one-shot markers), installs
 * the chainable crash handler (the customer's own handler keeps every
 * crash — nothing swallowed), folds any crash marker the previous run
 * left, reports the first launch of the paired release, and registers
 * the activity-lifecycle callbacks that open and close sessions — the
 * flushes ride the session ends, so there is no timer, no service, no
 * receiver, and no background battery cost (docs/telemetry-sdk.md).
 */
object ShipperDogAndroid {

    /** Starts the beacon and returns it for the explicit surfaces (opt-out, feedback handoff). */
    fun start(app: Application, config: TelemetryConfig): ShipperDog {
        val queueDirectory = File(app.filesDir, "shipperdog-telemetry")
        val sdk = ShipperDog.start(
            TelemetryConfig(
                baseUrl = config.baseUrl,
                token = config.token,
                tenantId = config.tenantId,
                applicationId = config.applicationId,
                releaseId = config.releaseId,
                deviceId = config.deviceId,
                queueDirectory = queueDirectory,
                maxQueueEvents = config.maxQueueEvents,
            )
        )
        CrashHandler.install(sdk)
        sdk.foldPendingCrashMarker()
        sdk.onFirstLaunch()
        val gate = ForegroundGate(sdk)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                gate.onActivityStarted()
            }

            override fun onActivityStopped(activity: Activity) {
                gate.onActivityStopped()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
        return sdk
    }

    /** The feedback handoff's deep link as an ACTION_VIEW intent (PRD FR-027). */
    fun feedbackIntent(sdk: ShipperDog, portalBaseUrl: String, crashSummary: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(sdk.feedbackUrl(portalBaseUrl, crashSummary)))
}

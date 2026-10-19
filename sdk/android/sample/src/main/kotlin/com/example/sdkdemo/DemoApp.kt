package com.example.sdkdemo

import android.app.Application
import android.util.Log
import com.shipperdog.ota.ApplyResult
import com.shipperdog.ota.OtaConfig
import com.shipperdog.ota.OtaManifest
import com.shipperdog.ota.OtaUpdater
import com.shipperdog.ota.android.ShipperDogOtaAndroid
import com.shipperdog.telemetry.TelemetryConfig
import com.shipperdog.telemetry.android.ShipperDogAndroid
import java.io.File

/**
 * The SDKs' complete Android integration (issues #72 and #174): this
 * file is the whole of it — the telemetry config value and ONE start
 * call in `Application.onCreate`, plus the OTA updater's config, its
 * apply callback, and ONE start call. The OTA poll loop, the
 * check/download/verify funnel, the lifecycle reports, the offline
 * event queue, sessions, install reporting, and crash context are
 * the two start calls' own; nothing else in the app changes.
 */
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val telemetry = TelemetryConfig(
            baseUrl = "https://shipperdog.example.com",
            token = "sdt1.paste-from-the-download-answer",
            tenantId = "00000000-0000-0000-0000-000000000001",
            applicationId = "00000000-0000-0000-0000-000000000002",
            releaseId = "00000000-0000-0000-0000-000000000003",
        )
        ShipperDogAndroid.start(this, telemetry)

        val ota = OtaConfig(
            baseUrl = "https://shipperdog.example.com",
            appToken = "sdc1.paste-the-fleet-token-the-console-issued",
            deviceId = "the-device-own-identifier",
            platform = "android",
            appVersion = "2.0.0",
            channel = "ota",
            verificationKeys = mapOf(
                // The application's ACTIVE signing key: Base64 of the
                // raw 32-byte Ed25519 public key, copyable in the console.
                "0123456789abcdef" to "paste-the-base64-public-key",
            ),
        )
        otaUpdater = ShipperDogOtaAndroid.start(this, ota, applier = ::applyBundle)
    }

    companion object {

        /** The updater the app keeps for the explicit surfaces (rollback reporting, debug queue depth). */
        lateinit var otaUpdater: OtaUpdater

        /**
         * The app's own bundle-swap act — the SDK hands over the
         * VERIFIED bundle; what an "apply" means (hot-load, next-launch
         * staging) is the host's alone.
         */
        private fun applyBundle(manifest: OtaManifest, bundle: File): ApplyResult {
            Log.i("DemoApp", "applying bundle " + manifest.bundleVersion)
            return if (manifest.forced || bundle.length() > 0) ApplyResult.Applied else ApplyResult.Refused
        }
    }
}

package com.example.sdkdemo

import android.app.Application
import com.shipperdog.telemetry.TelemetryConfig
import com.shipperdog.telemetry.android.ShipperDogAndroid

/**
 * The SDK's complete Android integration (issue #72, S15-01, AC1):
 * this file is the whole of it — the config value (pasted from the
 * portal download answer's telemetry block) and ONE start call in
 * `Application.onCreate`. Sessions, install reporting, crash
 * context, offline batching, and flushes are the start call's own;
 * nothing else in the app changes.
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
    }
}

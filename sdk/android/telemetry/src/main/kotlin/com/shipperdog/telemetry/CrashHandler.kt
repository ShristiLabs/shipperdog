package com.shipperdog.telemetry

/**
 * The crash-context chain (issue #72, S15-01, AC5): installs an
 * uncaught-exception handler that (1) writes the durable crash
 * marker — a file write, the only act safe on a failing process, and
 * (2) ALWAYS delegates to the handler that was installed before it.
 * Nothing is swallowed: the customer's own crash reporting
 * (Crashlytics, Sentry, Bugsnag — PRD 34's line) keeps every crash
 * exactly as before; the SDK's telemetry rides beside it, never
 * instead of it.
 *
 * The recorded marker folds into a session summary on the next
 * launch ([ShipperDog.foldPendingCrashMarker]) — the stack itself is
 * deliberately NOT shipped: the SDK reports crash COUNTS (the
 * crash-free metric's numerator), the deep-linked feedback handoff
 * carries a capped summary when the tester chooses to report, and
 * full stack analysis stays in the customer's tool.
 */
object CrashHandler {

    /**
     * Installs the chainable handler over whatever is currently
     * installed (including the system default, which is passed
     * through when no customer handler exists). Idempotent per SDK
     * instance marker: the marker file is written once per crash.
     */
    fun install(sdk: ShipperDog) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                sdk.recordCrashMarkerNow()
            } catch (markerFailure: Throwable) {
                // Never let telemetry take the crash path down.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

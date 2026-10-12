package com.shipperdog.telemetry

/**
 * The foreground gate (issue #72, S15-01): the pure counting logic
 * behind Android's activity-lifecycle callbacks — the first visible
 * activity opens a session, the last one to leave closes it. Kept in
 * the zero-dependency core so the JVM test suite pins the counting
 * (overlapping activities, rapid foreground/background churn, the
 * double-stop that must not close twice); the Android adapter is the
 * four-line mapping onto [android.app.Application.ActivityLifecycleCallbacks].
 */
class ForegroundGate(private val sdk: ShipperDog) {

    private var started: Int = 0

    private var crashed: Boolean = false

    /** An activity started: opens the session on 0 -> 1. */
    fun onActivityStarted() {
        started++
        if (started == 1) {
            sdk.onSessionStart()
        }
    }

    /** An activity stopped: closes the session on 1 -> 0. */
    fun onActivityStopped() {
        if (started <= 0) {
            return
        }
        started--
        if (started == 0) {
            val crashed = this.crashed
            this.crashed = false
            sdk.onSessionEnd(crashed)
            sdk.flush()
        }
    }

    /** Flags the open session as crash-bound (the crash handler's marker sets it at next start). */
    fun markCrashed() {
        crashed = true
    }
}

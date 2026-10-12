package com.shipperdog.telemetry

import java.util.UUID

/**
 * One telemetry event of the frozen S11-04 contract's vocabulary
 * (docs/analytics.md): the SDK emits INSTALL (first launch of the
 * paired release), SESSION summaries (the session/crash/ANR counts),
 * and UNINSTALL never (a device cannot report its own removal — the
 * platform's uninstall metric derives from the documented absence
 * heuristics, docs/telemetry-sdk.md). TESTER_VERDICT stays a portal
 * surface (a considered act, not telemetry).
 *
 * Every event carries a client-generated [eventId] (a fresh UUID per
 * event) — the (device, release, event id) dedup key that makes the
 * offline queue's at-least-once retries idempotent server-side.
 */
internal data class TelemetryEvent(
    val type: Type,
    val eventId: String = UUID.randomUUID().toString(),
    val result: Result? = null,
    val deviceId: String? = null,
    val sessions: Int = 0,
    val crashSessions: Int = 0,
    val anrSessions: Int = 0,
) {
    enum class Type {
        INSTALL,
        SESSION,
        UNINSTALL,
    }

    enum class Result {
        SUCCEEDED,
        FAILED,
    }

    init {
        if (type == Type.INSTALL) {
            require(result != null) { "INSTALL carries a result" }
        }
        if (type == Type.SESSION) {
            require(sessions > 0) { "a SESSION summary carries a positive session count" }
            require(crashSessions >= 0 && anrSessions >= 0) { "crash/ANR counts are non-negative" }
            require(crashSessions + anrSessions <= sessions) { "crash + ANR sessions stay inside the total" }
        }
    }

    companion object {
        /** The first-launch report of the paired release. */
        fun install(): TelemetryEvent = TelemetryEvent(type = Type.INSTALL, result = Result.SUCCEEDED)

        /** One session summary: [sessions] sessions with [crashes] of them crashing (ANR is host-reported or zero). */
        fun session(sessions: Int, crashes: Int, anrs: Int = 0): TelemetryEvent =
            TelemetryEvent(
                type = Type.SESSION,
                sessions = sessions,
                crashSessions = crashes,
                anrSessions = anrs,
            )
    }
}

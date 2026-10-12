import Foundation

/// One telemetry event of the frozen S11-04 contract's vocabulary
/// (docs/analytics.md): INSTALL (first launch of the paired release)
/// and SESSION summaries (session/crash/ANR counts). UNINSTALL is
/// deliberately not emitted — a device cannot report its own removal
/// (the platform's uninstall metric derives from the documented
/// absence heuristics); TESTER_VERDICT stays a portal surface.
///
/// Every event carries a fresh client `eventId` (UUID) — the
/// (device, release, event id) dedup key that makes the offline
/// queue's at-least-once retries idempotent server-side.
struct TelemetryEvent {

    enum Kind: String {
        case install = "INSTALL"
        case session = "SESSION"
    }

    enum Result: String {
        case succeeded = "SUCCEEDED"
    }

    let kind: Kind
    let eventId: UUID
    let result: Result?
    let sessions: Int
    let crashSessions: Int
    let anrSessions: Int

    private init(kind: Kind, eventId: UUID = UUID(), result: Result? = nil,
                 sessions: Int = 0, crashSessions: Int = 0, anrSessions: Int = 0) {
        self.kind = kind
        self.eventId = eventId
        self.result = result
        self.sessions = sessions
        self.crashSessions = crashSessions
        self.anrSessions = anrSessions
    }

    /// The first-launch report of the paired release.
    static func install() -> TelemetryEvent {
        TelemetryEvent(kind: .install, result: .succeeded)
    }

    /// One session summary: `sessions` sessions with `crashes` of
    /// them crashing (the host reports ANR separately or zero).
    static func session(sessions: Int, crashes: Int, anrs: Int = 0) -> TelemetryEvent {
        precondition(sessions > 0 && crashes >= 0 && anrs >= 0 && crashes + anrs <= sessions,
                     "a SESSION summary carries positive sessions with crash/ANR counts inside the total")
        return TelemetryEvent(kind: .session, sessions: sessions, crashSessions: crashes, anrSessions: anrs)
    }

    /// The event as the frozen contract's wire shape (camelCase names).
    func payload(config: TelemetryConfig, occurredAtIso: String) -> [String: Any] {
        var payload: [String: Any] = [
            "type": kind.rawValue,
            "releaseId": config.releaseId,
            "eventId": eventId.uuidString,
            "occurredAt": occurredAtIso,
        ]
        if let result = result {
            payload["result"] = result.rawValue
        }
        if let deviceId = config.deviceId {
            payload["deviceId"] = deviceId
        }
        if kind == .session {
            payload["sessions"] = sessions
            payload["crashSessions"] = crashSessions
            payload["anrSessions"] = anrSessions
        }
        return payload
    }
}

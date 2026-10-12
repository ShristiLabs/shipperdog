import Foundation

/// The crash-context chain (issue #72, S15-01, AC5): installs an
/// uncaught-exception handler that (1) writes the durable crash
/// marker — a file write, the only act safe on a failing process —
/// and (2) ALWAYS delegates to the handler installed before it.
/// Nothing is swallowed: the customer's own crash reporting keeps
/// every crash exactly as before (PRD 34 — the SDK reports crash
/// COUNTS beside it, never instead of it).
///
/// Scope, stated honestly: NSException crashes are the covered set
/// (the handler the runtime offers); SIGSEGV/SIGABRT-level faults
/// terminate the process before any handler can act — full signal
/// coverage is crash ANALYTICS, deliberately not this beacon's job.
/// The marker folds into a session summary on the next launch
/// (`foldPendingCrashMarker`); the stack itself is never shipped.
enum CrashReporter {

    /// The C-handler's storage: a C function pointer captures nothing,
    /// so the chain's state lives here — one SDK instance per process
    /// (the integration's own shape: one start call).
    private final class ChainState {
        static let lock = NSLock()
        static var sdk: ShipperDogTelemetry?
        static var previous: (@convention(c) (NSException) -> Void)?
    }

    /// Installs the chainable handler over whatever is currently
    /// installed (nil previous — the runtime's — passes through).
    static func install(sdk: ShipperDogTelemetry) {
        ChainState.lock.lock()
        ChainState.sdk = sdk
        ChainState.previous = NSGetUncaughtExceptionHandler()
        ChainState.lock.unlock()
        NSSetUncaughtExceptionHandler { exception in
            ChainState.sdk?.recordCrashMarkerNow()
            ChainState.previous?(exception)
        }
    }
}

#if canImport(UIKit)
import UIKit

/// The UIKit session wiring (AC1's one-call integration): foreground
/// opens the session, background closes it AND flushes — no timers,
/// no background fetch, no push; the system's own lifecycle moments
/// are the whole cadence (the zero-background-battery posture).
extension ShipperDogTelemetry {

    /// Registers the lifecycle observers (call once after `start`,
    /// from the application did-finish-launching path; the SDK's
    /// demo shows the two lines together).
    @discardableResult
    public func installSessionTracking() -> Self {
        foldPendingCrashMarker()
        onFirstLaunch()
        let center = NotificationCenter.default
        center.addObserver(forName: UIApplication.didBecomeActiveNotification,
                           object: nil, queue: .main) { [weak self] _ in
            self?.onSessionStart()
        }
        center.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                           object: nil, queue: .main) { [weak self] _ in
            guard let self else { return }
            self.onSessionEnd()
            // The flush rides a short background task: the system's
            // own grace window, never a background mode.
            let task = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
            DispatchQueue.global(qos: .utility).async {
                self.flush()
                if task != .invalid {
                    UIApplication.shared.endBackgroundTask(task)
                }
            }
        }
        return self
    }

    /// Opens the feedback deep link (PRD FR-027) in the system
    /// browser — the portal's version page with the report trigger.
    public func openFeedback(portalBaseUrl: String, crashSummary: String? = nil) {
        guard let url = URL(string: feedbackUrl(portalBaseUrl: portalBaseUrl, crashSummary: crashSummary)) else {
            return
        }
        UIApplication.shared.open(url)
    }
}
#endif

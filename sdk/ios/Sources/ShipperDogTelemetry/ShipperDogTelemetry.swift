import Foundation

/// The tester telemetry beacon (issue #72, S15-01, PRD 37): the small
/// library a test build embeds to report install confirmation,
/// session summaries, and crash context to ShipperDog's frozen events
/// contract. A beacon, not a framework: no timers and no background
/// work — the host's lifecycle moments (foreground, background, next
/// launch after a crash) drive everything, the zero-background-
/// battery posture (docs/telemetry-sdk.md).
///
/// Identity is structural: the token is the only credential and the
/// only tester identity; the payload carries the paired device id at
/// most. `optOut()` drops the queued history and stops all reporting.
public final class ShipperDogTelemetry {

    private let config: TelemetryConfig
    private let transport: HttpTransport
    private let queue: EventQueue
    private let lock = NSLock()
    private var optedOut = false
    private var sessionOpen = false

    init(config: TelemetryConfig, transport: HttpTransport) {
        self.config = config
        self.transport = transport
        self.queue = EventQueue(directory: config.queueDirectory, maxEvents: config.maxQueueEvents)
    }

    /// Starts the beacon: the SDK's one entry point (the UIKit
    /// session wiring is `installSessionTracking`).
    public static func start(config: TelemetryConfig) -> ShipperDogTelemetry {
        ShipperDogTelemetry(config: config, transport: URLSessionTransport())
    }

    /// The first launch of the paired release: the INSTALL report,
    /// exactly once per release (the marker is durable).
    public func onFirstLaunch() {
        guard !optedOutFlag, !installMarkerExists() else { return }
        writeInstallMarker()
        enqueue(.install())
    }

    /// A session begins (host lifecycle: foreground).
    public func onSessionStart() {
        guard !optedOutFlag else { return }
        lock.lock()
        sessionOpen = true
        lock.unlock()
    }

    /// A session ends (host lifecycle: background): one session
    /// summary, crash-flagged when the crash reporter caught one.
    public func onSessionEnd(crashed: Bool = false) {
        guard !optedOutFlag else { return }
        lock.lock()
        let open = sessionOpen
        sessionOpen = false
        lock.unlock()
        if open || crashed {
            enqueue(.session(sessions: 1, crashes: crashed ? 1 : 0))
        }
    }

    /// True while a crash marker from the previous run awaits folding.
    public var hasPendingCrashMarker: Bool {
        guard let marker = crashMarkerUrl() else { return false }
        return FileManager.default.fileExists(atPath: marker.path)
    }

    /// The crash fold of the NEXT launch: the marker the crash
    /// reporter wrote becomes the session summary the queue carries
    /// (one session, one crash). A no-op without a marker.
    public func foldPendingCrashMarker() {
        guard !optedOutFlag else { return }
        guard let marker = crashMarkerUrl(), FileManager.default.fileExists(atPath: marker.path) else { return }
        try? FileManager.default.removeItem(at: marker)
        enqueue(.session(sessions: 1, crashes: 1))
    }

    /// Flushes the offline queue synchronously: batches of at most
    /// the contract ceiling (500), one release per batch, each POST
    /// authenticated by the token. Accepted (2xx) and permanently
    /// refused (4xx — the batch's own shape) batches leave the queue;
    /// transport failures and 5xx KEEP the batch (at-least-once,
    /// idempotent server-side by the client event id). One attempt
    /// per batch per call — the host's next lifecycle moment is the
    /// retry cadence.
    ///
    /// The UIKit wiring calls this on `didEnterBackground` from a
    /// background task; hosts may call it wherever they like.
    @discardableResult
    public func flush() -> Int {
        guard !optedOutFlag else { return 0 }
        var accepted = 0
        while true {
            let next = queue.drain(limit: TelemetryConfig.maxBatchEvents)
            guard !next.isEmpty else { return accepted }
            let body = batchBody(next)
            let status: Int
            do {
                status = try transport.post(url: config.ingestUrl, bearerToken: config.token, jsonBody: body)
            } catch {
                return accepted
            }
            switch status {
            case 200...299:
                queue.removeOldest(next.count)
                accepted += 1
            case 400...499:
                queue.removeOldest(next.count)
            default:
                return accepted
            }
        }
    }

    /// The tester's explicit opt-out: stop reporting, drop history.
    public func optOut() {
        lock.lock()
        optedOut = true
        lock.unlock()
        queue.clear()
        try? installMarkerUrl().map(FileManager.default.removeItem(at:))
        try? crashMarkerUrl().map(FileManager.default.removeItem(at:))
    }

    /// The feedback handoff (PRD FR-027): the tester portal's version
    /// deep link with the report trigger and an OPTIONAL capped crash
    /// summary prefill, percent-encoded as UTF-8 (a URL parameter, not
    /// a log shipper — the full stack stays in the customer's own
    /// crash tooling, PRD 34).
    public func feedbackUrl(portalBaseUrl: String, crashSummary: String? = nil) -> String {
        var url = config.portalVersionUrl(portalBaseUrl: portalBaseUrl) + "?report=1"
        if let summary = crashSummary?.trimmingCharacters(in: .whitespacesAndNewlines), !summary.isEmpty {
            let capped = String(summary.prefix(TelemetryConfig.maxPrefillChars))
            let collapsed = capped.components(separatedBy: CharacterSet.whitespacesAndNewlines)
                .filter { !$0.isEmpty }
                .joined(separator: " ")
            let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
            if let encoded = collapsed.addingPercentEncoding(withAllowedCharacters: allowed) {
                url += "&hint=" + encoded
            }
        }
        return url
    }

    /// The queue's depth (debug surfaces).
    public var pendingEvents: Int { queue.count }

    // MARK: - Internals

    /// Writes the crash marker WITHOUT touching the queue (the only
    /// act safe on a failing process).
    func recordCrashMarkerNow() {
        guard let marker = crashMarkerUrl() else { return }
        try? FileManager.default.createDirectory(at: marker.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? "crash".write(to: marker, atomically: true, encoding: .utf8)
    }

    private var optedOutFlag: Bool {
        lock.lock(); defer { lock.unlock() }
        return optedOut
    }

    private func enqueue(_ event: TelemetryEvent) {
        let occurredAt = ISO8601DateFormatter().string(from: Date())
        queue.append([QueuedEvent(jsonObject: event.payload(config: config, occurredAtIso: occurredAt),
                                  occurredAtIso: occurredAt)])
    }

    private func batchBody(_ events: [QueuedEvent]) -> String {
        let objects = events.map(\.jsonObject)
        let data = (try? JSONSerialization.data(withJSONObject: ["events": objects])) ?? Data("{\"events\":[]}".utf8)
        return String(data: data, encoding: .utf8) ?? "{\"events\":[]}"
    }

    private func installMarkerExists() -> Bool {
        guard let url = installMarkerUrl() else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }

    private func writeInstallMarker() {
        guard let url = installMarkerUrl() else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                 withIntermediateDirectories: true)
        try? "sent".write(to: url, atomically: true, encoding: .utf8)
    }

    private func installMarkerUrl() -> URL? {
        markerDirectory()?.appendingPathComponent("install-\(config.releaseId).sent")
    }

    private func crashMarkerUrl() -> URL? {
        markerDirectory()?.appendingPathComponent("crash-\(config.releaseId).marker")
    }

    private func markerDirectory() -> URL? {
        config.queueDirectory?.appendingPathComponent("markers", isDirectory: true)
    }
}

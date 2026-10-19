import Foundation

/// The OTA lifecycle reporter (issue #175, S16-13; ADR-0010 D4): the
/// five device lifecycle events of the frozen S16-08 ingest family —
/// OTA_SEEN, OTA_DOWNLOAD SUCCEEDED|FAILED, OTA_APPLIED
/// SUCCEEDED|FAILED, OTA_FAILED checksum_mismatch|apply_error|
/// signature_invalid, and OTA_ROLLED_BACK — riding the manifest's
/// `sdt1.` grant through the EXISTING batch ingest
/// (`POST /api/v1/tenants/{tenantId}/analytics/events`), the
/// telemetry beacon's own flush policy: batches of at most 500,
/// accepted (2xx) and permanently refused (4xx) batches removed,
/// transport failures and 5xx kept queued — at-least-once,
/// idempotent server-side by the client event id.
///
/// The grant is BOUND by the check: `onManifest` is the bind (the
/// tenant, release, and bundle every subsequent event names); before
/// the first manifest there is nothing to report and no credential to
/// report it with, so enqueues are no-ops.
final class OtaReporter {

    private let config: OtaConfig
    private let http: OtaHttp
    private let now: () -> Date
    private let queue: OtaEventQueue
    private let lock = NSLock()
    private var binding: Binding?

    private final class Binding {
        let grant: IngestGrant
        let bundleId: String
        let token: String

        init(grant: IngestGrant, bundleId: String, token: String) {
            self.grant = grant
            self.bundleId = bundleId
            self.token = token
        }
    }

    init(config: OtaConfig, http: OtaHttp, now: @escaping () -> Date = { Date() }) {
        self.config = config
        self.http = http
        self.now = now
        self.queue = OtaEventQueue(directory: config.queueDirectory, maxEvents: config.maxQueueEvents)
    }

    /// The check's bind: the grant the manifest minted and the bundle
    /// its events name.
    func onManifest(_ manifest: OtaManifest) {
        lock.lock(); defer { lock.unlock() }
        binding = IngestGrant.parse(manifest.ingestToken).map {
            Binding(grant: $0, bundleId: manifest.bundleId, token: manifest.ingestToken)
        }
    }

    /// The seen report — one per served manifest (the funnel's seen stage).
    func seen() {
        enqueue(type: "OTA_SEEN", result: nil)
    }

    /// The download stage's two legs.
    func downloadSucceeded() {
        enqueue(type: "OTA_DOWNLOAD", result: "SUCCEEDED")
    }

    func downloadFailed() {
        enqueue(type: "OTA_DOWNLOAD", result: "FAILED")
    }

    /// The apply stage's two legs (the app-supplied applier's own
    /// declared outcome).
    func applied() {
        enqueue(type: "OTA_APPLIED", result: "SUCCEEDED")
    }

    func applyRefused() {
        enqueue(type: "OTA_APPLIED", result: "FAILED")
    }

    /// The failure family: the three-word reason vocabulary rides
    /// `result`, stored as given.
    func failedChecksumMismatch() {
        enqueue(type: "OTA_FAILED", result: "checksum_mismatch")
    }

    func failedSignatureInvalid() {
        enqueue(type: "OTA_FAILED", result: "signature_invalid")
    }

    func failedApplyError() {
        enqueue(type: "OTA_FAILED", result: "apply_error")
    }

    /// The rollback report (the app's own post-apply verdict;
    /// resultless by shape).
    func rolledBack(detail: String? = nil) {
        enqueue(type: "OTA_ROLLED_BACK", result: nil, detail: detail)
    }

    /// Flushes the queue under the bound grant; answers the number of
    /// accepted batches. One attempt per batch per call — the host's
    /// next poll (or lifecycle moment) is the retry cadence.
    @discardableResult
    func flush() -> Int {
        lock.lock()
        let bound = binding
        lock.unlock()
        guard let bound else { return 0 }
        var accepted = 0
        while true {
            let next = queue.drain(limit: OtaReporter.maxBatchEvents)
            guard !next.isEmpty else { return accepted }
            let body = OtaReporter.batchBody(next)
            let status: Int
            do {
                status = try http.post(url: bound.grant.ingestUrl(baseUrl: config.baseUrl),
                                       bearerToken: bound.token,
                                       jsonBody: body)
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

    /// The queue's depth (the host surfaces it in debug builds).
    var pendingEvents: Int { queue.count }

    private func enqueue(type: String, result: String?, detail: String? = nil) {
        lock.lock()
        let bound = binding
        lock.unlock()
        guard let bound else { return }
        let occurredAt = ISO8601DateFormatter().string(from: now())
        var payload: [String: Any] = [
            "type": type,
            "releaseId": bound.grant.releaseId.uuidString.lowercased(),
            "eventId": UUID().uuidString.lowercased(),
            "occurredAt": occurredAt,
            "otaBundleId": bound.bundleId,
        ]
        if let result {
            payload["result"] = result
        }
        var otaPayload: [String: String] = [:]
        if !config.deviceId.isEmpty {
            otaPayload["device_id"] = config.deviceId
        }
        if let detail = detail?.trimmingCharacters(in: .whitespacesAndNewlines), !detail.isEmpty {
            otaPayload["detail"] = String(detail.prefix(OtaReporter.maxDetailChars))
        }
        if !otaPayload.isEmpty {
            payload["otaPayload"] = otaPayload
        }
        // The #174 liveness lesson, structural here: the queue's own
        // persist swallows its failures, so a report that cannot
        // reach the disk drops ONE queued word and never breaks the
        // funnel transition that observed it.
        queue.append([OtaQueuedEvent(jsonObject: payload, occurredAtIso: occurredAt)])
    }

    private static func batchBody(_ events: [OtaQueuedEvent]) -> String {
        let objects = events.map(\.jsonObject)
        let data = (try? JSONSerialization.data(withJSONObject: ["events": objects])) ?? Data("{\"events\":[]}".utf8)
        return String(data: data, encoding: .utf8) ?? "{\"events\":[]}"
    }

    /// The frozen contract's batch ceiling (docs/analytics.md).
    static let maxBatchEvents = 500

    /// The payload detail word's cap: a report member, not a log shipper.
    static let maxDetailChars = 128
}

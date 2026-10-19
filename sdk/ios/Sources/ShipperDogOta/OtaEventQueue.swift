import Foundation

/// One serialized queue entry: the event's JSON object beside the
/// persisted occurredAt (the wire instant never re-mints across
/// retries — the dedup id and the timestamp are minted together).
struct OtaQueuedEvent {
    let jsonObject: [String: Any]
    let occurredAtIso: String

    var json: String {
        guard let data = try? JSONSerialization.data(withJSONObject: jsonObject, options: [.sortedKeys]) else {
            return "{}"
        }
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// The queue line form: `json<US>occurredAt` (the JSON itself can
    /// never contain the separator — JSONSerialization escapes
    /// control characters).
    var line: String { "\(json)\u{1F}\(occurredAtIso)" }

    static func from(line: String) -> OtaQueuedEvent? {
        guard let separator = line.firstIndex(of: "\u{1F}") else {
            return nil
        }
        let jsonPart = String(line[line.startIndex..<separator])
        let occurredAt = String(line[line.index(after: separator)...])
        guard let data = jsonPart.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        return OtaQueuedEvent(jsonObject: object, occurredAtIso: occurredAt)
    }
}

/// The offline lifecycle-event queue (issue #175, S16-13): the
/// telemetry SDK's `EventQueue` doctrine applied to the OTA family —
/// a bounded, file-backed FIFO of serialized events. Reports land on
/// the funnel transitions that observe them and leave in `drain`
/// batches the reporter POSTs; anything the POST does not confirm
/// (transport failure, 5xx) STAYS queued for the next flush —
/// at-least-once delivery, idempotent server-side by the client
/// event id (V54's dedup key). The bound drops the OLDEST first: the
/// freshest funnel report is always what survives.
final class OtaEventQueue {

    private let lock = NSLock()
    private let fileUrl: URL?
    private let maxEvents: Int
    private var events: [OtaQueuedEvent] = []
    private var loaded = false

    init(directory: URL?, maxEvents: Int) {
        self.fileUrl = directory?.appendingPathComponent("ota-events.jsonl")
        self.maxEvents = maxEvents
        if let directory = directory {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    var count: Int {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        return events.count
    }

    /// Appends events, dropping the oldest first at the bound. A
    /// persist that cannot write keeps the in-memory truth — the
    /// enqueue the #174 review hardened: one failed report write
    /// never breaks the update cycle that observed the event.
    func append(_ newEvents: [OtaQueuedEvent]) {
        guard !newEvents.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        events.append(contentsOf: newEvents)
        if events.count > maxEvents {
            events.removeFirst(events.count - maxEvents)
        }
        persist()
    }

    /// The next `limit` events, oldest first — nothing is removed
    /// (the flush confirms before removal).
    func drain(limit: Int) -> [OtaQueuedEvent] {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        return Array(events.prefix(limit))
    }

    /// Removes the oldest `count` events (only after their batch was
    /// accepted or permanently refused).
    func removeOldest(_ count: Int) {
        lock.lock(); defer { lock.unlock() }
        loadIfNeeded()
        guard count > 0, !events.isEmpty else { return }
        events.removeFirst(Swift.min(count, events.count))
        persist()
    }

    /// Drops everything.
    func clear() {
        lock.lock(); defer { lock.unlock() }
        events.removeAll()
        if let fileUrl = fileUrl {
            try? FileManager.default.removeItem(at: fileUrl)
        }
    }

    private func loadIfNeeded() {
        guard !loaded, let fileUrl = fileUrl else { return }
        loaded = true
        guard let lines = try? String(contentsOf: fileUrl, encoding: .utf8) else { return }
        events = lines.split(separator: "\n").compactMap { OtaQueuedEvent.from(line: String($0)) }
    }

    private func persist() {
        guard let fileUrl = fileUrl else { return }
        let body = events.map(\.line).joined(separator: "\n")
        // Write-then-rename: a crash mid-write never truncates the
        // queue (the previous file stays whole until the rename).
        let tmp = fileUrl.appendingPathExtension("tmp")
        do {
            try body.data(using: .utf8)?.write(to: tmp, options: .atomic)
            _ = try FileManager.default.replaceItemAt(fileUrl, withItemAt: tmp)
        } catch {
            // A failed persist keeps the in-memory truth; the next
            // append retries.
        }
    }
}

import Foundation

/// One serialized queue entry: the event's JSON object beside the
/// persisted occurredAt (the wire instant never re-mints across
/// retries — the dedup id and the timestamp are minted together).
struct QueuedEvent {
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

    static func from(line: String) -> QueuedEvent? {
        guard let separator = line.firstIndex(of: "\u{1F}") else {
            return nil
        }
        let jsonPart = String(line[line.startIndex..<separator])
        let occurredAt = String(line[line.index(after: separator)...])
        guard let data = jsonPart.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        return QueuedEvent(jsonObject: object, occurredAtIso: occurredAt)
    }
}

/// The offline event queue (issue #72, S15-01, AC4): a bounded,
/// file-backed FIFO of serialized events. Events land on the
/// lifecycle moments that observe them and leave in batches the
/// flush loop POSTs; anything unconfirmed STAYS queued —
/// at-least-once delivery, idempotent server-side by the client
/// event id. The bound drops the OLDEST first: a device offline for
/// weeks never grows an unbounded disk liability, and the freshest
/// telemetry is always what survives.
final class EventQueue {

    private let lock = NSLock()
    private let fileUrl: URL?
    private let maxEvents: Int
    private var events: [QueuedEvent] = []
    private var loaded = false

    init(directory: URL?, maxEvents: Int) {
        self.fileUrl = directory?.appendingPathComponent("events.jsonl")
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

    /// Appends events, dropping the oldest first at the bound.
    func append(_ newEvents: [QueuedEvent]) {
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
    func drain(limit: Int) -> [QueuedEvent] {
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

    /// Drops everything (the opt-out's data hygiene).
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
        events = lines.split(separator: "\n").compactMap { QueuedEvent.from(line: String($0)) }
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

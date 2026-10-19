import XCTest
@testable import ShipperDogOta

/// The offline queue's behavior contract (issue #175, S16-13): the
/// telemetry `EventQueue` doctrine's own legs — bounded with
/// oldest-dropped-first, drain-without-removal, removal only after
/// confirmation, and persistence across instances (a process death
/// loses nothing the next flush cannot carry).
final class OtaEventQueueTests: XCTestCase {

    private var directory: URL!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-ota-queue-\(UUID().uuidString)", isDirectory: true)
    }

    private func event(_ n: Int) -> OtaQueuedEvent {
        OtaQueuedEvent(jsonObject: ["n": n], occurredAtIso: "2026-10-17T12:00:0\(n % 10)Z")
    }

    func testTheBoundDropsTheOldestFirst() {
        let queue = OtaEventQueue(directory: directory.appendingPathComponent("bounded", isDirectory: true),
                                  maxEvents: 3)
        queue.append((1...5).map(event))
        XCTAssertEqual(3, queue.count)
        XCTAssertEqual([3, 4, 5], queue.drain(limit: 10).map { $0.jsonObject["n"] as? Int })
    }

    func testDrainDoesNotRemoveAndRemovalNeedsItsCount() {
        let queue = OtaEventQueue(directory: directory.appendingPathComponent("drain", isDirectory: true),
                                  maxEvents: 10)
        queue.append([event(1), event(2)])
        XCTAssertEqual(1, queue.drain(limit: 1).count)
        XCTAssertEqual(2, queue.count)
        queue.removeOldest(1)
        XCTAssertEqual(1, queue.count)
        XCTAssertEqual(2, queue.drain(limit: 10)[0].jsonObject["n"] as? Int)
    }

    func testAFreshInstanceResumesFromThePersistedFile() {
        let persist = directory.appendingPathComponent("persist", isDirectory: true)
        let queue = OtaEventQueue(directory: persist, maxEvents: 10)
        queue.append([event(1), event(2)])
        let next = OtaEventQueue(directory: persist, maxEvents: 10)
        XCTAssertEqual(2, next.count)
        XCTAssertEqual(1, next.drain(limit: 10)[0].jsonObject["n"] as? Int)
        XCTAssertEqual("2026-10-17T12:00:01Z", next.drain(limit: 10)[0].occurredAtIso)
    }

    func testANilDirectoryKeepsEverythingInMemory() {
        let queue = OtaEventQueue(directory: nil, maxEvents: 10)
        queue.append([event(1)])
        XCTAssertEqual(1, queue.count)
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent("memory").path))
    }

    func testClearDropsEverything() {
        let shared = directory.appendingPathComponent("clear", isDirectory: true)
        let queue = OtaEventQueue(directory: shared, maxEvents: 10)
        queue.append([event(1), event(2)])
        queue.clear()
        XCTAssertEqual(0, queue.count)
        XCTAssertEqual(0, OtaEventQueue(directory: shared, maxEvents: 10).count)
    }

    // The #174 review's liveness finding, held structural: a persist
    // that cannot write keeps the in-memory truth — one failed report
    // write never breaks the cycle that observed the event.

    func testAPersistThatCannotWriteKeepsTheInMemoryTruth() throws {
        let broken = directory.appendingPathComponent("broken", isDirectory: true)
        try FileManager.default.createDirectory(at: broken, withIntermediateDirectories: true)
        // The write-then-rename target is a DIRECTORY: the tmp write
        // fails, the rename never runs, and the swallow keeps the
        // queue's own answer whole.
        try FileManager.default.createDirectory(
            at: broken.appendingPathComponent("ota-events.jsonl.tmp"), withIntermediateDirectories: true)
        let queue = OtaEventQueue(directory: broken, maxEvents: 10)
        queue.append([event(1), event(2)])
        XCTAssertEqual(2, queue.count)
        XCTAssertEqual([1, 2], queue.drain(limit: 10).map { $0.jsonObject["n"] as? Int })
        queue.removeOldest(1)
        XCTAssertEqual(1, queue.count)
    }

    func testCorruptLinesAreSkippedAndTheGoodOnesSurviveTheLoad() throws {
        let persist = directory.appendingPathComponent("corrupt", isDirectory: true)
        let queue = OtaEventQueue(directory: persist, maxEvents: 10)
        queue.append([event(1)])
        // A hand-corrupted file: a separator-less line, then a line
        // whose JSON part is not JSON, around one whole entry.
        let good = try String(contentsOf: persist.appendingPathComponent("ota-events.jsonl"), encoding: .utf8)
        let hand = "a line with no separator\nnot json\u{1F}2026-10-17T12:00:00Z\n" + good
        try hand.write(to: persist.appendingPathComponent("ota-events.jsonl"),
                       atomically: true, encoding: .utf8)
        let reloaded = OtaEventQueue(directory: persist, maxEvents: 10)
        XCTAssertEqual(1, reloaded.count)
        XCTAssertEqual(1, reloaded.drain(limit: 10)[0].jsonObject["n"] as? Int)
    }

    func testTheLineFormSurvivesValuesThatWouldEatItsSeparator() {
        // JSONSerialization escapes control characters, so the unit
        // separator can never appear inside the JSON half — the line
        // form stays one line, and a detail word with a newline rides
        // escaped, round-tripping whole.
        let entry = OtaQueuedEvent(jsonObject: ["detail": "line one\nline two\u{1F}not a break"],
                                   occurredAtIso: "2026-10-17T12:00:00Z")
        XCTAssertEqual(1, entry.json.components(separatedBy: "\n").count)
        XCTAssertFalse(entry.json.contains("\u{1F}"))
        let back = OtaQueuedEvent.from(line: entry.line)
        XCTAssertEqual(entry.jsonObject["detail"] as? String, back?.jsonObject["detail"] as? String)
        XCTAssertEqual(entry.occurredAtIso, back?.occurredAtIso)
        XCTAssertNil(OtaQueuedEvent.from(line: "{}"))
    }
}

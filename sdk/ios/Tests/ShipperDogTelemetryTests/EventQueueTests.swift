import XCTest
@testable import ShipperDogTelemetry

/// The offline queue's contract (issue #72, S15-01, AC4): FIFO order,
/// the oldest-dropped-first bound, persistence across instances, and
/// the opt-out's clear.
final class EventQueueTests: XCTestCase {

    private var directory: URL!

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-queue-\(UUID().uuidString)", isDirectory: true)
    }

    private func entry(_ tag: String) -> QueuedEvent {
        QueuedEvent(jsonObject: ["type": "INSTALL", "tag": tag], occurredAtIso: "occurred-\(tag)")
    }

    func testDrainsOldestFirstAndRemovesOnlyConfirmed() {
        let queue = EventQueue(directory: directory, maxEvents: 100)
        queue.append([entry("a"), entry("b"), entry("c")])
        XCTAssertEqual(queue.drain(limit: 2).map { $0.jsonObject["tag"] as? String }, ["a", "b"])
        XCTAssertEqual(queue.count, 3)
        queue.removeOldest(2)
        XCTAssertEqual(queue.drain(limit: 10).map { $0.jsonObject["tag"] as? String }, ["c"])
    }

    func testTheBoundDropsTheOldestFirst() {
        let queue = EventQueue(directory: directory, maxEvents: 3)
        queue.append([entry("a"), entry("b"), entry("c")])
        queue.append([entry("d")])
        XCTAssertEqual(queue.drain(limit: 10).map { $0.jsonObject["tag"] as? String }, ["b", "c", "d"])
    }

    func testPersistsAcrossInstances() {
        EventQueue(directory: directory, maxEvents: 100).append([entry("a"), entry("b")])
        let reloaded = EventQueue(directory: directory, maxEvents: 100)
        XCTAssertEqual(reloaded.drain(limit: 10).map { $0.jsonObject["tag"] as? String }, ["a", "b"])
        XCTAssertEqual(reloaded.drain(limit: 10).map(\.occurredAtIso), ["occurred-a", "occurred-b"])
    }

    func testClearDropsEverythingForTheOptOut() {
        let queue = EventQueue(directory: directory, maxEvents: 100)
        queue.append([entry("a"), entry("b")])
        queue.clear()
        XCTAssertEqual(queue.count, 0)
        XCTAssertEqual(EventQueue(directory: directory, maxEvents: 100).count, 0)
    }
}

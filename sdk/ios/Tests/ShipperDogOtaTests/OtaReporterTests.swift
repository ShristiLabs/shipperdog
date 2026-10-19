import XCTest
@testable import ShipperDogOta

/// The reporter's flush-policy legs the funnel tests do not reach
/// (issue #175, S16-13; the frozen S16-08 ingest contract): a
/// permanently-refused 4xx batch is dropped, never retried; batches
/// carry at most the contract's 500 events; and the rollback detail
/// word is capped at the report member's own bound.
final class OtaReporterTests: XCTestCase {

    private let tenantId = "11111111-1111-1111-1111-111111111111"

    private let releaseId = "33333333-3333-3333-3333-333333333333"

    private func ingestToken() -> String {
        let payload = "1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(releaseId)||1893456000"
        let urlSafe = Data(payload.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        return "sdt1." + urlSafe + ".bm9uY2U"
    }

    private var manifest: OtaManifest {
        OtaManifest(bundleId: "22222222-2222-2222-2222-222222222222",
                    bundleVersion: "1.4.2",
                    url: "https://downloads.example.com/api/v1/downloads/t",
                    sha256: "aabb",
                    signature: "c2ln",
                    keyId: "0123456789abcdef",
                    sizeBytes: nil,
                    forced: false,
                    minAppVersion: nil,
                    ingestToken: ingestToken(),
                    pollIntervalSeconds: 60)
    }

    /// Records every posted body; answers a scriptable status.
    private final class PostHttp: OtaHttp {
        var status: Int
        var bodies: [String] = []

        init(status: Int = 201) {
            self.status = status
        }

        func get(url: String, bearerToken: String) throws -> OtaHttpResponse {
            OtaHttpResponse(status: 204, body: nil)
        }

        func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int { 200 }

        func post(url: String, bearerToken: String, jsonBody: String) throws -> Int {
            bodies.append(jsonBody)
            return status
        }
    }

    private static let fixedInstant = ISO8601DateFormatter().date(from: "2026-10-17T12:00:00Z")!

    private let now: () -> Date = { OtaReporterTests.fixedInstant }

    private func reporter(_ http: PostHttp) -> OtaReporter {
        OtaReporter(config: OtaConfig(
            baseUrl: "https://shipperdog.example.com",
            appToken: "sdc1.fleet-token",
            deviceId: "device-reporter-1",
            platform: "ios",
            appVersion: "2.0.0",
            channel: "ota",
            queueDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("sd-ota-reporter-\(UUID().uuidString)", isDirectory: true),
            maxQueueEvents: 2_000),
                    http: http,
                    now: now)
    }

    func testAPermanentlyRefusedFourHundredBatchIsDroppedNeverRetried() {
        let http = PostHttp(status: 400)
        let reporter = reporter(http)
        reporter.onManifest(manifest)
        reporter.rolledBack()
        XCTAssertEqual(1, reporter.pendingEvents)
        XCTAssertEqual(0, reporter.flush())
        XCTAssertEqual(1, http.bodies.count)
        XCTAssertEqual(0, reporter.pendingEvents)
        // The refusal was permanent: with no new events, a later
        // flush posts nothing — the refused batch is gone, not
        // retried.
        XCTAssertEqual(0, reporter.flush())
        XCTAssertEqual(1, http.bodies.count)
        // A fresh event is still attempted (here, refused again).
        reporter.rolledBack()
        XCTAssertEqual(0, reporter.flush())
        XCTAssertEqual(2, http.bodies.count)
        XCTAssertEqual(0, reporter.pendingEvents)
    }

    func testBatchesCarryAtMostTheFrozenFiveHundredEvents() {
        let http = PostHttp()
        let reporter = reporter(http)
        reporter.onManifest(manifest)
        for _ in 0..<1_001 {
            reporter.rolledBack()
        }
        XCTAssertEqual(3, reporter.flush())
        XCTAssertEqual(3, http.bodies.count)
        let counts = http.bodies.map { body in
            body.components(separatedBy: "\"type\"").count - 1
        }
        XCTAssertEqual([500, 500, 1], counts)
        XCTAssertEqual(0, reporter.pendingEvents)
    }

    func testTheRollbackDetailWordIsCappedAtItsOwnBound() {
        let http = PostHttp()
        let reporter = reporter(http)
        reporter.onManifest(manifest)
        reporter.rolledBack(detail: String(repeating: "x", count: 300))
        reporter.flush()
        let body = http.bodies[0]
        XCTAssertTrue(body.contains("\"detail\":\"\(String(repeating: "x", count: OtaReporter.maxDetailChars))\""))
        XCTAssertFalse(body.contains(String(repeating: "x", count: OtaReporter.maxDetailChars + 1)))
    }

    func testBeforeTheFirstManifestThereIsNothingToReportAndNoCredentialToReportItWith() {
        let http = PostHttp()
        let reporter = reporter(http)
        reporter.rolledBack()
        XCTAssertEqual(0, reporter.pendingEvents)
        XCTAssertEqual(0, reporter.flush())
        XCTAssertEqual(0, http.bodies.count)
    }

    func testTheEventShapeIsTheFrozenIngestContract() {
        let http = PostHttp()
        let reporter = reporter(http)
        reporter.onManifest(manifest)
        reporter.rolledBack(detail: "crash_loop")
        reporter.flush()
        let body = http.bodies[0]
        XCTAssertTrue(body.hasPrefix("{\"events\":["))
        XCTAssertTrue(body.contains("\"type\":\"OTA_ROLLED_BACK\""))
        XCTAssertFalse(body.contains("\"result\""))
        XCTAssertTrue(body.contains("\"releaseId\":\"\(releaseId)\""))
        XCTAssertTrue(body.contains("\"otaBundleId\":\"22222222-2222-2222-2222-222222222222\""))
        XCTAssertTrue(body.contains("\"otaPayload\":{"))
        XCTAssertTrue(body.contains("\"device_id\":\"device-reporter-1\""))
        XCTAssertTrue(body.contains("\"detail\":\"crash_loop\""))
        XCTAssertTrue(body.contains("\"eventId\":\""))
        XCTAssertTrue(body.contains("\"occurredAt\":\"2026-10-17T12:00:00Z\""))
    }

    func testEveryUuidOnTheWireIsLowercaseHexParityWithTheBackend() {
        // Java's UUID.toString() is lowercase; the ingest schema's
        // `releaseId` is format uuid, and the backend stores the
        // string as given. The SDK normalizes Swift's UPPERCASE
        // uuidString to the same lowercase parity — even when the
        // grant itself carried uppercase blocks.
        let upperRelease = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"
        let payload = "1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(upperRelease)||1893456000"
        let urlSafe = Data(payload.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        let upperManifest = OtaManifest(bundleId: "22222222-2222-2222-2222-222222222222",
                                        bundleVersion: "1.4.2",
                                        url: "https://downloads.example.com/api/v1/downloads/t",
                                        sha256: "aabb",
                                        signature: "c2ln",
                                        keyId: "0123456789abcdef",
                                        sizeBytes: nil,
                                        forced: false,
                                        minAppVersion: nil,
                                        ingestToken: "sdt1." + urlSafe + ".bm9uY2U",
                                        pollIntervalSeconds: 60)
        let http = PostHttp()
        let reporter = reporter(http)
        reporter.onManifest(upperManifest)
        reporter.rolledBack()
        reporter.flush()
        let body = http.bodies[0]
        XCTAssertTrue(body.contains("\"releaseId\":\"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa\""))
        XCTAssertFalse(body.contains(upperRelease))
        // The dedup key's own parity: every eventId is 36 lowercase
        // hex-and-dash characters, never an uppercase Swift uuidString.
        let ids = body.components(separatedBy: "\"eventId\":\"").dropFirst()
            .map { String($0.prefix { $0 != "\"" }) }
        XCTAssertEqual(1, ids.count)
        XCTAssertEqual(36, ids[0].count)
        XCTAssertEqual(ids[0], ids[0].lowercased())
    }
}

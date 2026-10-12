import XCTest
@testable import ShipperDogTelemetry

/// The beacon's behavior contract (issue #72, S15-01): the install
/// report's once-per-release discipline, session summaries, the
/// crash marker's next-launch fold, the flush policy (accepted and
/// permanently refused leave the queue; transport failures and 5xx
/// stay — at-least-once, idempotent server-side by the client event
/// id), the batch ceiling across a deep queue, the opt-out's data
/// hygiene, and the feedback deep link.
final class ShipperDogTelemetryTests: XCTestCase {

    private var directory: URL!

    final class RecordingTransport: HttpTransport {
        var statuses: [Int]
        var posts: [(url: String, token: String, body: String)] = []

        init(statuses: [Int] = [200]) {
            self.statuses = statuses
        }

        func post(url: String, bearerToken: String, jsonBody: String) throws -> Int {
            posts.append((url, bearerToken, jsonBody))
            let status = statuses.isEmpty ? 200 : statuses.removeFirst()
            if status == -1 {
                throw URLError(.notConnectedToInternet)
            }
            return status
        }
    }

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-sdk-\(UUID().uuidString)", isDirectory: true)
    }

    private func config(deviceId: String? = nil, dirKey: String = "shared") -> TelemetryConfig {
        TelemetryConfig(
            baseUrl: "https://shipperdog.example.com",
            token: "sdt1.fixture-token",
            tenantId: "11111111-1111-1111-1111-111111111111",
            applicationId: "22222222-2222-2222-2222-222222222222",
            releaseId: "33333333-3333-3333-3333-333333333333",
            deviceId: deviceId,
            queueDirectory: directory.appendingPathComponent(dirKey, isDirectory: true))
    }

    func testInstallReportsOncePerRelease() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(), transport: transport)
        sdk.onFirstLaunch()
        sdk.onFirstLaunch()
        XCTAssertEqual(sdk.flush(), 1)
        // A fresh instance of the same release (same directory) does
        // not re-report: the marker is durable.
        let second = ShipperDogTelemetry(config: config(), transport: transport)
        second.onFirstLaunch()
        XCTAssertEqual(second.flush(), 0)
        XCTAssertEqual(transport.posts.count, 1)
        XCTAssertTrue(transport.posts[0].body.contains(#""type":"INSTALL""#))
    }

    func testSessionEndsReportSummaries() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(), transport: transport)
        sdk.onSessionStart()
        sdk.onSessionEnd()
        sdk.flush()
        XCTAssertTrue(transport.posts[0].body.contains(#""sessions":1"#))
        XCTAssertTrue(transport.posts[0].body.contains(#""crashSessions":0"#))
    }

    func testCrashMarkersFoldIntoTheNextLaunchSummary() {
        let transport = RecordingTransport()
        let crashed = ShipperDogTelemetry(config: config(), transport: transport)
        crashed.recordCrashMarkerNow()
        XCTAssertTrue(crashed.hasPendingCrashMarker)
        let next = ShipperDogTelemetry(config: config(), transport: transport)
        next.foldPendingCrashMarker()
        XCTAssertEqual(next.flush(), 1)
        XCTAssertTrue(transport.posts[0].body.contains(#""crashSessions":1"#))
        XCTAssertFalse(next.hasPendingCrashMarker)
    }

    func testFlushRemovesAcceptedAndRefusedBatchesButKeepsRetriableOnes() {
        let accepted = ShipperDogTelemetry(config: config(dirKey: "accepted"), transport: RecordingTransport(statuses: [200]))
        accepted.onFirstLaunch()
        XCTAssertEqual(accepted.flush(), 1)
        XCTAssertEqual(accepted.pendingEvents, 0)

        let refused = ShipperDogTelemetry(config: config(dirKey: "refused"), transport: RecordingTransport(statuses: [400]))
        refused.onFirstLaunch()
        XCTAssertEqual(refused.flush(), 0)
        XCTAssertEqual(refused.pendingEvents, 0)

        let hiccup = ShipperDogTelemetry(config: config(dirKey: "hiccup"), transport: RecordingTransport(statuses: [503, -1]))
        hiccup.onFirstLaunch()
        XCTAssertEqual(hiccup.flush(), 0)
        XCTAssertEqual(hiccup.pendingEvents, 1)
    }

    func testDeepQueuesFlushInContractSizedBatches() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(), transport: transport)
        for _ in 0..<1_201 {
            sdk.onSessionStart()
            sdk.onSessionEnd()
        }
        XCTAssertEqual(sdk.pendingEvents, 1_201)
        XCTAssertEqual(sdk.flush(), 3)
        let sizes = transport.posts.map { post in
            post.body.components(separatedBy: #""eventId""#).count - 1
        }
        XCTAssertEqual(sizes, [500, 500, 201])
        XCTAssertEqual(sdk.pendingEvents, 0)
    }

    func testPostsCarryTheIngestUrlAndRawTokenSeam() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(), transport: transport)
        sdk.onFirstLaunch()
        sdk.flush()
        XCTAssertEqual(
            transport.posts[0].url,
            "https://shipperdog.example.com/api/v1/tenants/11111111-1111-1111-1111-111111111111/analytics/events")
        // The transport seam carries the RAW token; the production
        // transport adds the Bearer scheme (URLSessionTransport).
        XCTAssertEqual(transport.posts[0].token, "sdt1.fixture-token")
    }

    func testThePairedDeviceRidesEveryEvent() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(deviceId: "44444444-4444-4444-4444-444444444444"),
                                      transport: transport)
        sdk.onFirstLaunch()
        sdk.flush()
        XCTAssertTrue(transport.posts[0].body.contains("44444444-4444-4444-4444-444444444444"))
    }

    func testOptOutStopsReportingAndDropsHistory() {
        let transport = RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config(), transport: transport)
        sdk.onFirstLaunch()
        sdk.onSessionStart()
        sdk.onSessionEnd()
        sdk.optOut()
        XCTAssertEqual(sdk.pendingEvents, 0)
        sdk.onSessionStart()
        sdk.onSessionEnd()
        XCTAssertEqual(sdk.flush(), 0)
        XCTAssertEqual(transport.posts.count, 0)
        // The install marker is gone too: a re-opt-in re-reports.
        let reOptIn = ShipperDogTelemetry(config: config(), transport: transport)
        reOptIn.onFirstLaunch()
        XCTAssertEqual(reOptIn.pendingEvents, 1)
    }

    func testTheFeedbackDeepLinkCarriesTheReportTriggerAndACappedHint() {
        let sdk = ShipperDogTelemetry(config: config(), transport: RecordingTransport())
        XCTAssertEqual(
            sdk.feedbackUrl(portalBaseUrl: "https://portal.example.com"),
            "https://portal.example.com/applications/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222?report=1")
        let capped = sdk.feedbackUrl(portalBaseUrl: "https://portal.example.com/",
                                     crashSummary: String(repeating: "x", count: 5_000))
        XCTAssertTrue(capped.contains("hint="))
        XCTAssertLessThan(capped.count, 2_100 + "https://portal.example.com/applications/".count + 100)
        // The hint percent-encodes as UTF-8: an astral scalar (emoji)
        // becomes its four-byte UTF-8 sequence, never truncated
        // UTF-16 code units.
        XCTAssertEqual(
            sdk.feedbackUrl(portalBaseUrl: "https://portal.example.com", crashSummary: "crash 🚨"),
            "https://portal.example.com/applications/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222?report=1&hint=crash%20%F0%9F%9A%A8")
    }
}

/// The crash chain (AC5): the installed handler writes the marker
/// AND delegates to the previously installed handler.
final class CrashReporterTests: XCTestCase {

    /// Global state for the C-handler seam (a @convention(c) closure
    /// captures nothing — the production handler itself works the
    /// same way through CrashReporter's ChainState).
    private enum DelegateProbe {
        static var ran = false
    }

    private let delegateProbe: @convention(c) (NSException) -> Void = { _ in
        DelegateProbe.ran = true
    }

    func testChainsThePreviousHandlerAndRecordsTheMarker() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-crash-\(UUID().uuidString)", isDirectory: true)
        let config = TelemetryConfig(
            baseUrl: "https://shipperdog.example.com", token: "sdt1.fixture",
            tenantId: "11111111-1111-1111-1111-111111111111",
            applicationId: "22222222-2222-2222-2222-222222222222",
            releaseId: "33333333-3333-3333-3333-333333333333",
            queueDirectory: directory)
        let transport = ShipperDogTelemetryTests.RecordingTransport()
        let sdk = ShipperDogTelemetry(config: config, transport: transport)

        DelegateProbe.ran = false
        NSSetUncaughtExceptionHandler(delegateProbe)
        CrashReporter.install(sdk: sdk)
        let handler = NSGetUncaughtExceptionHandler()
        XCTAssertNotNil(handler)
        handler?(NSException(name: NSExceptionName("fixture"), reason: "chain proof", userInfo: nil))
        XCTAssertTrue(DelegateProbe.ran, "the previous handler saw the crash")
        XCTAssertTrue(sdk.hasPendingCrashMarker, "the marker is written before the delegation")
    }
}

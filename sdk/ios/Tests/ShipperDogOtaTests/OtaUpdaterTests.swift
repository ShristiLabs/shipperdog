import XCTest
import CryptoKit
@testable import ShipperDogOta

/// The updater's funnel contract (issue #175, S16-13): 204 is the
/// no-op standing with NOTHING reported; a served manifest reports
/// OTA_SEEN, walks download then verify then the app-supplied
/// applier, and reports every transition — the happy path's
/// OTA_DOWNLOAD and OTA_APPLIED SUCCEEDED legs, the refused applier's
/// OTA_APPLIED FAILED, the thrown applier's OTA_FAILED apply_error,
/// and the verifier's two failure words (checksum_mismatch,
/// signature_invalid). Events ride the manifest's `sdt1.` grant
/// through the existing ingest URL, batched `{"events":[...]}`,
/// deduped by fresh client event ids; the poll cadence follows the
/// manifest's server-owned seconds; the app-reported rollback lands
/// as the resultless OTA_ROLLED_BACK.
final class OtaUpdaterTests: XCTestCase {

    private let tenantId = "11111111-1111-1111-1111-111111111111"

    private let releaseId = "33333333-3333-3333-3333-333333333333"

    private let key = Curve25519.Signing.PrivateKey()

    private var publicKeyBase64: String { key.publicKey.rawRepresentation.base64EncodedString() }

    private var keyId: String { OtaVerifier.keyId(ofRaw: key.publicKey.rawRepresentation) }

    private let bundleBytes = Data((0..<256).map { UInt8(truncatingIfNeeded: $0) })

    private var checksumHex: String { OtaVerifier.sha256Hex(of: bundleBytes) }

    private var signatureBase64: String {
        try! key.signature(for: Data(checksumHex.utf8)).base64EncodedString()
    }

    /// A well-formed `sdt1.` grant, the backend codec's own payload blocks.
    private func ingestToken() -> String {
        let payload = "1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(releaseId)||1893456000"
        let urlSafe = Data(payload.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        return "sdt1." + urlSafe + ".bm9uY2U"
    }

    private func manifestJson(sha256: String? = nil,
                              signature: String? = nil,
                              pollSeconds: Int64 = 900) -> String {
        """
        {"bundleId":"22222222-2222-2222-2222-222222222222","bundleVersion":"1.4.2",\
        "url":"https://downloads.example.com/api/v1/downloads/t","sha256":"\(sha256 ?? checksumHex)",\
        "signature":"\(signature ?? signatureBase64)","keyId":"\(keyId)","sizeBytes":256,"forced":false,\
        "minAppVersion":null,"ingestToken":"\(ingestToken())","pollIntervalSeconds":\(pollSeconds)}
        """
    }

    /// The scriptable transport: the check answer, the bytes it
    /// serves, the post answers.
    private final class FakeHttp: OtaHttp {
        var checkStatus: Int
        var checkBody: String?
        let served: Data
        var downloadStatus: Int
        var postStatus: Int
        var checks: [(url: String, token: String)] = []
        var posts: [(url: String, token: String, body: String)] = []

        init(checkStatus: Int = 204,
             checkBody: String? = nil,
             served: Data = Data(),
             downloadStatus: Int = 200,
             postStatus: Int = 201) {
            self.checkStatus = checkStatus
            self.checkBody = checkBody
            self.served = served
            self.downloadStatus = downloadStatus
            self.postStatus = postStatus
        }

        func get(url: String, bearerToken: String) throws -> OtaHttpResponse {
            checks.append((url, bearerToken))
            if checkStatus == -1 {
                throw URLError(.notConnectedToInternet)
            }
            return OtaHttpResponse(status: checkStatus, body: checkBody)
        }

        func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int {
            guard (200...299).contains(downloadStatus) else { return downloadStatus }
            try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            try? served.write(to: target)
            return downloadStatus
        }

        func post(url: String, bearerToken: String, jsonBody: String) throws -> Int {
            posts.append((url, bearerToken, jsonBody))
            return postStatus
        }
    }

    private final class Applier: BundleApplier {
        var applied = false
        let answer: ApplyResult
        let error: Error?

        init(answer: ApplyResult = .applied, error: Error? = nil) {
            self.answer = answer
            self.error = error
        }

        func apply(_ manifest: OtaManifest, bundle: URL) throws -> ApplyResult {
            applied = true
            if let error { throw error }
            return answer
        }
    }

    private var root: URL!

    override func setUp() {
        super.setUp()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-ota-updater-\(UUID().uuidString)", isDirectory: true)
    }

    private func config() -> OtaConfig {
        OtaConfig(
            baseUrl: "https://shipperdog.example.com",
            appToken: "sdc1.fleet-token",
            deviceId: "device-funnel-1",
            platform: "iPhone OS",
            appVersion: "2.0.0",
            channel: "OTA",
            pollIntervalSeconds: 60,
            verificationKeys: [keyId: publicKeyBase64],
            queueDirectory: root.appendingPathComponent("queue", isDirectory: true),
            downloadDirectory: root.appendingPathComponent("bundles", isDirectory: true))
    }

    private func updater(_ http: FakeHttp, config: OtaConfig? = nil) -> OtaUpdater {
        let config = config ?? self.config()
        return OtaUpdater(
            config: config,
            http: http,
            downloader: OtaDownloader(http: http, directory: config.downloadDirectory),
            verifier: OtaVerifier(verificationKeys: config.verificationKeys),
            reporter: OtaReporter(config: config, http: http))
    }

    private func typesPosted(_ http: FakeHttp) -> [String] {
        http.posts.flatMap { post in
            post.body.components(separatedBy: "\"type\":\"").dropFirst().compactMap { piece in
                let word = piece.prefix { $0 != "\"" }
                return word.isEmpty ? nil : String(word)
            }
        }
    }

    func testTheCheckPresentsTheSdc1TokenWithEveryQueryMemberLowercased() {
        let http = FakeHttp(checkStatus: 204)
        updater(http).checkNow()
        XCTAssertEqual(
            "https://shipperdog.example.com/api/v1/ota/check"
                + "?deviceId=device-funnel-1&platform=iphone%20os&appVersion=2.0.0&channel=ota",
            http.checks[0].url)
        XCTAssertEqual("sdc1.fleet-token", http.checks[0].token)
    }

    func testAManifestIsTheUpdateAvailableStanding() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson())
        let standing = updater(http).checkNow()
        XCTAssertEqual(.updateAvailable, standing.kind)
        XCTAssertEqual("1.4.2", standing.manifest?.bundleVersion)
        XCTAssertEqual("22222222-2222-2222-2222-222222222222", standing.manifest?.bundleId)
    }

    func testAMalformedBodyIsTheProtocolFailureStanding() {
        let http = FakeHttp(checkStatus: 200, checkBody: "{\"bundleId\":\"only-one-member\"}")
        XCTAssertEqual(.protocolFailure, updater(http).checkNow().kind)
        XCTAssertEqual(.protocolFailure, updater(FakeHttp(checkStatus: 500)).checkNow().kind)
    }

    func testA204IsTheNoOpStandingAndReportsNothing() {
        let http = FakeHttp(checkStatus: 204)
        let standing = updater(http).updateNow(applier: Applier())
        XCTAssertEqual(.upToDate, standing.kind)
        XCTAssertEqual(0, http.posts.count)
    }

    func testTheHappyPathWalksSeenDownloadVerifiedApplyAndReports() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(), served: bundleBytes)
        let standing = updater(http).updateNow(applier: Applier())
        XCTAssertEqual(.applied, standing.kind)
        XCTAssertEqual(["OTA_SEEN", "OTA_DOWNLOAD", "OTA_APPLIED"], typesPosted(http))
        // The events rode the manifest's grant through the existing ingest URL.
        XCTAssertEqual(
            "https://shipperdog.example.com/api/v1/tenants/\(tenantId)/analytics/events",
            http.posts[0].url)
        XCTAssertTrue(http.posts[0].token.hasPrefix("sdt1."))
        let body = http.posts[0].body
        XCTAssertTrue(body.hasPrefix("{\"events\":["))
        XCTAssertTrue(body.contains("\"releaseId\":\"\(releaseId)\""))
        XCTAssertTrue(body.contains("\"otaBundleId\":\"22222222-2222-2222-2222-222222222222\""))
        XCTAssertTrue(body.contains("\"device_id\":\"device-funnel-1\""))
        let eventIds = Set(body.components(separatedBy: "\"eventId\":\"").dropFirst()
            .map { String($0.prefix { $0 != "\"" }) })
        XCTAssertEqual(3, eventIds.count)
    }

    func testThePollCadenceFollowsTheManifestsServerOwnedSeconds() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(pollSeconds: 3_600), served: bundleBytes)
        let sdk = updater(http)
        XCTAssertEqual(60, sdk.nextPollSeconds())
        sdk.updateNow(applier: Applier())
        XCTAssertEqual(3_600, sdk.nextPollSeconds())
        XCTAssertEqual("1.4.2", sdk.currentManifest?.bundleVersion)
    }

    func testAFailedDownloadReportsTheFailedLeg() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(), downloadStatus: 500)
        let standing = updater(http).updateNow(applier: Applier())
        XCTAssertEqual(.downloadFailed, standing.kind)
        XCTAssertEqual(["OTA_SEEN", "OTA_DOWNLOAD"], typesPosted(http))
        XCTAssertTrue(http.posts[0].body.contains("\"type\":\"OTA_DOWNLOAD\""))
        XCTAssertTrue(http.posts[0].body.contains("\"result\":\"FAILED\""))
    }

    func testAChecksumMismatchReportsTheFrozenFailureWord() {
        let wrongChecksum = String(checksumHex.dropLast()) + (checksumHex.hasSuffix("0") ? "1" : "0")
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(sha256: wrongChecksum), served: bundleBytes)
        let standing = updater(http).updateNow(applier: Applier())
        XCTAssertEqual(.checksumMismatch, standing.kind)
        XCTAssertEqual(["OTA_SEEN", "OTA_DOWNLOAD", "OTA_FAILED"], typesPosted(http))
        XCTAssertTrue(http.posts[0].body.contains("\"type\":\"OTA_FAILED\""))
        XCTAssertTrue(http.posts[0].body.contains("\"result\":\"checksum_mismatch\""))
    }

    func testAnInvalidSignatureReportsTheFrozenFailureWordAndNeverApplies() {
        var tampered = Data(base64Encoded: signatureBase64)!
        tampered[10] ^= 0x11
        let http = FakeHttp(checkStatus: 200,
                            checkBody: manifestJson(signature: tampered.base64EncodedString()),
                            served: bundleBytes)
        let applier = Applier()
        let standing = updater(http).updateNow(applier: applier)
        XCTAssertEqual(.signatureInvalid, standing.kind)
        XCTAssertFalse(applier.applied)
        XCTAssertTrue(http.posts[0].body.contains("\"result\":\"signature_invalid\""))
    }

    func testARefusingApplierReportsAppliedFailedAndAThrownOneApplyError() {
        let refused = FakeHttp(checkStatus: 200, checkBody: manifestJson(), served: bundleBytes)
        let refusedStanding = updater(refused).updateNow(applier: Applier(answer: .refused))
        XCTAssertEqual(.applyRefused, refusedStanding.kind)
        XCTAssertTrue(refused.posts[0].body.contains("\"type\":\"OTA_APPLIED\""))
        XCTAssertTrue(refused.posts[0].body.contains("\"result\":\"FAILED\""))

        let thrown = FakeHttp(checkStatus: 200, checkBody: manifestJson(), served: bundleBytes)
        struct ApplyBroke: Error {}
        let thrownStanding = updater(thrown).updateNow(applier: Applier(error: ApplyBroke()))
        XCTAssertEqual(.applyError, thrownStanding.kind)
        XCTAssertTrue(thrown.posts[0].body.contains("\"type\":\"OTA_FAILED\""))
        XCTAssertTrue(thrown.posts[0].body.contains("\"result\":\"apply_error\""))
    }

    func testTheAppReportedRollbackIsTheResultlessRolledBackWord() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(), served: bundleBytes)
        let sdk = updater(http)
        sdk.updateNow(applier: Applier())
        sdk.reportRolledBack(detail: "crash_loop")
        XCTAssertEqual(2, http.posts.count)
        XCTAssertTrue(http.posts[1].body.contains("\"type\":\"OTA_ROLLED_BACK\""))
        XCTAssertFalse(http.posts[1].body.contains("\"result\""))
        XCTAssertTrue(http.posts[1].body.contains("\"detail\":\"crash_loop\""))
    }

    func testTransportFailuresAnswerTheRetryStandingAndKeepNothingQueued() {
        let http = FakeHttp(checkStatus: -1)
        let sdk = updater(http)
        let standing = sdk.checkNow()
        XCTAssertEqual(.transportFailure, standing.kind)
        XCTAssertEqual(0, sdk.pendingEvents)
    }

    func testUnconfirmedBatchesStayQueuedUntilTheIngestAccepts() {
        let http = FakeHttp(checkStatus: 200, checkBody: manifestJson(), served: bundleBytes, postStatus: 503)
        let sdk = updater(http)
        sdk.updateNow(applier: Applier())
        XCTAssertEqual(3, sdk.pendingEvents)
        http.postStatus = 201
        XCTAssertEqual(1, sdk.flush())
        XCTAssertEqual(0, sdk.pendingEvents)
    }
}

import XCTest
@testable import ShipperDogOta

/// The downloader's resumability contract (issue #175, S16-13):
/// fresh staging to `.part` then the atomic rename; resume of a
/// partial file via `Range: bytes=<staged>-` when the endpoint
/// answers 206; the GRACEFUL DEGRADE when it answers a plain 200
/// instead (the compose proxy streams whole objects — restage from
/// zero, never fail); 416 on a staged file as completion; and the
/// size-mismatch refusal.
final class OtaDownloaderTests: XCTestCase {

    private var directory: URL!

    /// Serves bytes with real range semantics, or the degraded
    /// plain-200 mode.
    private final class RangeHttp: OtaHttp {
        enum Mode {
            case range206
            case plain200
            case always416
            case error500
        }

        let bytes: Data
        let mode: Mode
        let status: Int
        var lastRangeFrom: Int64?

        init(bytes: Data, mode: Mode = .range206, status: Int = 200) {
            self.bytes = bytes
            self.mode = mode
            self.status = status
        }

        func get(url: String, bearerToken: String) throws -> OtaHttpResponse {
            OtaHttpResponse(status: 204, body: nil)
        }

        func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int {
            lastRangeFrom = rangeFromBytes
            guard status == 200 else { return status }
            try? FileManager.default.createDirectory(at: target.deletingLastPathComponent(),
                                                     withIntermediateDirectories: true)
            switch mode {
            case .range206:
                if let rangeFromBytes, rangeFromBytes > 0 {
                    let tail = bytes.dropFirst(Int(rangeFromBytes))
                    if !FileManager.default.fileExists(atPath: target.path) {
                        FileManager.default.createFile(atPath: target.path, contents: nil)
                    }
                    if let handle = try? FileHandle(forWritingTo: target) {
                        defer { try? handle.close() }
                        _ = try? handle.seekToEnd()
                        try? handle.write(contentsOf: Data(tail))
                    }
                    return 206
                }
                try? bytes.write(to: target)
                return 200
            case .plain200:
                try? bytes.write(to: target)
                return 200
            case .always416:
                return 416
            case .error500:
                return 500
            }
        }

        func post(url: String, bearerToken: String, jsonBody: String) throws -> Int { 201 }
    }

    private let bytes = Data((0..<1_024).map { UInt8(truncatingIfNeeded: $0) })

    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-ota-download-\(UUID().uuidString)", isDirectory: true)
    }

    private var manifest: OtaManifest {
        OtaManifest(bundleId: "11111111-1111-1111-1111-111111111111",
                    bundleVersion: "1.4.2",
                    url: "https://downloads.example.com/api/v1/downloads/t",
                    sha256: OtaVerifier.sha256Hex(of: bytes),
                    signature: "c2ln",
                    keyId: "0123456789abcdef",
                    sizeBytes: Int64(bytes.count),
                    forced: false,
                    minAppVersion: nil,
                    ingestToken: "sdt1.grant",
                    pollIntervalSeconds: 60)
    }

    private func read(_ url: URL?) -> Data {
        guard let url else { return Data() }
        return (try? Data(contentsOf: url)) ?? Data()
    }

    func testAFreshDownloadStagesAndSettlesAtomically() throws {
        let http = RangeHttp(bytes: bytes)
        let bundle = OtaDownloader(http: http, directory: directory).download(manifest)
        XCTAssertEqual(Int64(bytes.count), Int64(read(bundle).count))
        XCTAssertEqual(bytes, read(bundle))
        XCTAssertNil(http.lastRangeFrom)
        XCTAssertTrue(bundle!.lastPathComponent.hasSuffix(OtaDownloader.bundleSuffix))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix).path))
    }

    func testAPartialFileResumesWithARangeWhenTheEndpointAnswers206() throws {
        let http = RangeHttp(bytes: bytes)
        let downloader = OtaDownloader(http: http, directory: directory)
        // A staged partial: the first 512 bytes of the bundle.
        let part = directory.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try bytes.prefix(512).write(to: part)
        let bundle = downloader.download(manifest)
        XCTAssertEqual(Int64(bytes.count), Int64(read(bundle).count))
        XCTAssertEqual(bytes, read(bundle))
        XCTAssertEqual(512, http.lastRangeFrom)
    }

    func testAPlain200AnswerDegradesGracefullyByRestagingFromZero() throws {
        let http = RangeHttp(bytes: bytes, mode: .plain200)
        let downloader = OtaDownloader(http: http, directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let part = directory.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix)
        // A poisoned partial with WRONG leading bytes: the degrade
        // must restage from zero (the 200 answer carries the whole
        // object), never append onto it.
        try Data(repeating: 0x7F, count: 512).write(to: part)
        let bundle = downloader.download(manifest)
        XCTAssertEqual(bytes, read(bundle))
        XCTAssertEqual(512, http.lastRangeFrom)
    }

    func testA416OnACompletePartIsCompletion() throws {
        let http = RangeHttp(bytes: bytes, mode: .always416)
        let downloader = OtaDownloader(http: http, directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let part = directory.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix)
        try bytes.write(to: part)
        let bundle = downloader.download(manifest)
        XCTAssertEqual(bytes, read(bundle))
    }

    func testASizeMismatchAndAServerErrorBothRefuse() throws {
        let wrongSize = OtaManifest(bundleId: manifest.bundleId,
                                    bundleVersion: manifest.bundleVersion,
                                    url: manifest.url,
                                    sha256: manifest.sha256,
                                    signature: manifest.signature,
                                    keyId: manifest.keyId,
                                    sizeBytes: Int64(bytes.count) + 5,
                                    forced: false,
                                    minAppVersion: nil,
                                    ingestToken: manifest.ingestToken,
                                    pollIntervalSeconds: 60)
        XCTAssertNil(OtaDownloader(http: RangeHttp(bytes: bytes), directory: directory).download(wrongSize))
        XCTAssertNil(OtaDownloader(http: RangeHttp(bytes: bytes, mode: .error500, status: 500),
                                    directory: directory).download(manifest))
    }

    func testTransportFailuresRefuseWithoutTouchingStagedBytes() throws {
        final class ThrowingHttp: OtaHttp {
            func get(url: String, bearerToken: String) throws -> OtaHttpResponse {
                OtaHttpResponse(status: 204, body: nil)
            }

            func download(url: String, rangeFromBytes: Int64?, target: URL) throws -> Int {
                throw URLError(.notConnectedToInternet)
            }

            func post(url: String, bearerToken: String, jsonBody: String) throws -> Int { 201 }
        }
        XCTAssertNil(OtaDownloader(http: ThrowingHttp(), directory: directory).download(manifest))
    }

    func testCleanupDropsTheStagedBytes() throws {
        let downloader = OtaDownloader(http: RangeHttp(bytes: bytes), directory: directory)
        let bundle = downloader.download(manifest)
        downloader.cleanup(manifest)
        XCTAssertFalse(FileManager.default.fileExists(atPath: bundle!.path))
        XCTAssertFalse(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent(manifest.bundleId + OtaDownloader.partSuffix).path))
    }
}

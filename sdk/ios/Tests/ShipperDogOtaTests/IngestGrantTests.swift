import XCTest
@testable import ShipperDogOta

/// The ingest grant reader (issue #175, S16-13; ADR-0010 D4): the
/// `sdt1.` token the manifest mints carries the reporting tenant and
/// the release its lifecycle events name — the payload blocks the
/// backend codec joins. The reader tolerates everything the wire can
/// hand it and refuses nothing loudly: garbage is nil (the reporter
/// runs unbound, the funnel never blocks on a bad grant).
final class IngestGrantTests: XCTestCase {

    private let tenantId = "11111111-1111-1111-1111-111111111111"

    private let releaseId = "33333333-3333-3333-3333-333333333333"

    private let deviceId = "44444444-4444-4444-4444-444444444444"

    private func token(_ payload: String) -> String {
        let urlSafe = Data(payload.utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
        return "sdt1." + urlSafe + ".c2ln"
    }

    func testReadsTheGrantBlocksTheBackendCodecJoins() {
        let grant = IngestGrant.parse(
            token("1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(releaseId)|\(deviceId)|1893456000"))
        XCTAssertEqual(UUID(uuidString: tenantId), grant?.tenantId)
        XCTAssertEqual(UUID(uuidString: releaseId), grant?.releaseId)
        XCTAssertEqual(UUID(uuidString: deviceId), grant?.deviceId)
    }

    func testADevicelessGrantParsesWithNilDevice() {
        let grant = IngestGrant.parse(
            token("1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(releaseId)||1893456000"))
        XCTAssertEqual(UUID(uuidString: tenantId), grant?.tenantId)
        XCTAssertEqual(UUID(uuidString: releaseId), grant?.releaseId)
        XCTAssertNil(grant?.deviceId)
    }

    func testTheIngestUrlIsTheFrozenRouteUnderTheConfigBase() {
        let grant = IngestGrant.parse(
            token("1|\(tenantId)|00000000-0000-0000-0000-000000000009|\(releaseId)||1893456000"))
        XCTAssertEqual(
            "https://shipperdog.example.com/api/v1/tenants/\(tenantId)/analytics/events",
            grant?.ingestUrl(baseUrl: "https://shipperdog.example.com/"))
    }

    func testMalformedGrantsAreNilNeverThrown() {
        XCTAssertNil(IngestGrant.parse(nil))
        XCTAssertNil(IngestGrant.parse(""))
        XCTAssertNil(IngestGrant.parse("not-a-token"))
        XCTAssertNil(IngestGrant.parse("sdc1.other-prefix"))
        XCTAssertNil(IngestGrant.parse("sdt1.noblocks"))
        XCTAssertNil(IngestGrant.parse(token("2|\(tenantId)|t|\(releaseId)||1893456000")))
        XCTAssertNil(IngestGrant.parse(token("one|two|three")))
        XCTAssertNil(IngestGrant.parse(token("1|not-a-uuid|t|\(releaseId)||1893456000")))
        XCTAssertNil(IngestGrant.parse("sdt1.!!!not-base64!!!.x"))
        XCTAssertNil(IngestGrant.parse(token("1|\(tenantId)|t|\(releaseId)|not-a-uuid|1893456000")))
    }
}

import XCTest
@testable import ShipperDogTelemetry

/// The wire contract of the SDK's payloads (issue #72, S15-01): the
/// frozen events contract's exact members — camelCase names, the
/// type's own shape, the client `eventId` on every event, the paired
/// device when configured.
final class TelemetryEventTests: XCTestCase {

    private let config = TelemetryConfig(
        baseUrl: "https://shipperdog.example.com",
        token: "sdt1.fixture",
        tenantId: "11111111-1111-1111-1111-111111111111",
        applicationId: "22222222-2222-2222-2222-222222222222",
        releaseId: "33333333-3333-3333-3333-333333333333")

    func testInstallPayloadCarriesTheContractShape() {
        let event = TelemetryEvent.install()
        let payload = event.payload(config: config, occurredAtIso: "2026-10-12T12:00:00Z")
        XCTAssertEqual(payload["type"] as? String, "INSTALL")
        XCTAssertEqual(payload["result"] as? String, "SUCCEEDED")
        XCTAssertEqual(payload["releaseId"] as? String, "33333333-3333-3333-3333-333333333333")
        XCTAssertEqual(payload["occurredAt"] as? String, "2026-10-12T12:00:00Z")
        XCTAssertNotNil(payload["eventId"] as? String)
        XCTAssertNil(payload["sessions"])
    }

    func testSessionPayloadCarriesTheCountsAndPairedDevice() {
        let paired = TelemetryConfig(
            baseUrl: config.baseUrl, token: config.token, tenantId: config.tenantId,
            applicationId: config.applicationId, releaseId: config.releaseId,
            deviceId: "44444444-4444-4444-4444-444444444444")
        let payload = TelemetryEvent.session(sessions: 3, crashes: 1, anrs: 0)
            .payload(config: paired, occurredAtIso: "t")
        XCTAssertEqual(payload["sessions"] as? Int, 3)
        XCTAssertEqual(payload["crashSessions"] as? Int, 1)
        XCTAssertEqual(payload["anrSessions"] as? Int, 0)
        XCTAssertEqual(payload["deviceId"] as? String, "44444444-4444-4444-4444-444444444444")
        XCTAssertNil(payload["result"])
    }

    func testEveryEventMintsAFreshDedupId() {
        let a = TelemetryEvent.install()
        let b = TelemetryEvent.install()
        XCTAssertNotEqual(a.eventId, b.eventId)
    }
}

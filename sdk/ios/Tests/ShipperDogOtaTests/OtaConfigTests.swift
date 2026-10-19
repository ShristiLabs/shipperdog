import XCTest
@testable import ShipperDogOta

/// The config's wire and boundary contract (issue #175, S16-13): the
/// check query against the frozen baseline.yaml surface — every
/// member the device reports, lowercased and percent-encoded exactly
/// once (RFC 3986's unreserved alphabet, the telemetry SDK's own),
/// the OPTIONAL `bundleVersion` present only when the device carries
/// a label — and the construction-time bounds that mirror the check
/// surface's own validation (S16-07) plus D8's frozen key shapes, so
/// a misconfigured SDK fails before the network ever does.
///
/// The bounds themselves answer Swift's `precondition` — the
/// programmer-error crash path the telemetry config also uses — so
/// the suite proves the shapes the constructor ACCEPTS; the refusals
/// are fatal by design and not assertable in-process (the same
/// posture as the telemetry sibling's suite).
final class OtaConfigTests: XCTestCase {

    private func config(deviceId: String = "device-1",
                        platform: String = "iOS",
                        appVersion: String = "2.0.0",
                        channel: String = "OTA",
                        bundleVersion: String? = nil,
                        verificationKeys: [String: String] = [:],
                        pollIntervalSeconds: Int64 = 60) -> OtaConfig {
        OtaConfig(
            baseUrl: "https://shipperdog.example.com",
            appToken: "sdc1.fleet-token",
            deviceId: deviceId,
            platform: platform,
            appVersion: appVersion,
            channel: channel,
            bundleVersion: bundleVersion,
            pollIntervalSeconds: pollIntervalSeconds,
            verificationKeys: verificationKeys)
    }

    func testTheCheckQueryCarriesEveryMemberEncodedAndLowercased() {
        // The members the wire can hand the encoder: a space, an
        // ampersand, mixed case — the query must stay ONE query.
        let query = config(deviceId: "dev 1&x",
                           platform: "iPhone OS",
                           channel: "Canary",
                           bundleVersion: "1.4.1").checkQuery
        XCTAssertEqual(
            "deviceId=dev%201%26x&platform=iphone%20os&appVersion=2.0.0&bundleVersion=1.4.1&channel=canary",
            query)
    }

    func testTheOptionalBundleVersionMemberIsAbsentUntilTheDeviceCarriesALabel() {
        XCTAssertFalse(config().checkQuery.contains("bundleVersion"))
        XCTAssertTrue(config(bundleVersion: "1.4.1").checkQuery.contains("&bundleVersion=1.4.1&"))
    }

    func testTheFrozenRouteDerivesFromTheConfiguredBase() {
        XCTAssertEqual("https://shipperdog.example.com/api/v1/ota/check", config().checkUrl)
        XCTAssertEqual("https://shipperdog.example.com/api/v1/ota/check",
                       OtaConfig(
                        baseUrl: "https://shipperdog.example.com/",
                        appToken: "sdc1.fleet-token",
                        deviceId: "device-1",
                        platform: "ios",
                        appVersion: "2.0.0",
                        channel: "ota").checkUrl)
    }

    func testTheAppTokenMustBeTheSdc1FleetCredential() {
        XCTAssertEqual("sdc1.", OtaConfig.appTokenPrefix)
        XCTAssertTrue(config().appToken.hasPrefix(OtaConfig.appTokenPrefix))
    }

    func testVerificationKeysAcceptOnlyTheFrozenShapes() {
        let rawKey = Data(repeating: 0x2A, count: OtaConfig.ed25519KeyBytes)
        let keyId = OtaVerifier.keyId(ofRaw: rawKey)
        XCTAssertEqual(OtaConfig.keyIdLength, keyId.count)
        // The frozen shape passes: a 16-hex id over Base64 of 32 raw
        // bytes (unpadded Base64 included — the console may emit it).
        let padded = rawKey.base64EncodedString()
        _ = config(verificationKeys: [keyId: padded])
        _ = config(verificationKeys: [keyId: padded.replacingOccurrences(of: "=", with: "")])
    }

    func testTheBoundsMirrorTheCheckSurfacesOwnValidation() {
        // The accepted extremes: the boundary members construct.
        _ = config(deviceId: String(repeating: "d", count: OtaConfig.maxDeviceIdChars),
                   platform: String(repeating: "p", count: OtaConfig.maxPlatformChars),
                   appVersion: String(repeating: "v", count: OtaConfig.maxAppVersionChars),
                   channel: String(repeating: "c", count: OtaConfig.maxChannelChars),
                   bundleVersion: String(repeating: "b", count: OtaConfig.maxBundleVersionChars))
        XCTAssertEqual(128, OtaConfig.maxDeviceIdChars)
        XCTAssertEqual(64, OtaConfig.maxPlatformChars)
        XCTAssertEqual(64, OtaConfig.maxAppVersionChars)
        XCTAssertEqual(64, OtaConfig.maxChannelChars)
        XCTAssertEqual(128, OtaConfig.maxBundleVersionChars)
    }
}

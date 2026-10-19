import XCTest
@testable import ShipperDogOta

/// The manifest parser's frozen wire contract (issue #175, S16-13),
/// pinned against docs/openapi/baseline.yaml's `Manifest` schema —
/// LITERAL JSON fixtures, never a round-trip of our own writer: the
/// exact member names, the null-versus-absent optionality
/// (`sizeBytes`, `minAppVersion`), the absent-`forced` default the
/// schema allows, forward tolerance of members a newer baseline adds,
/// and the refusals the updater reads as its protocol-failure
/// standing.
final class OtaManifestTests: XCTestCase {

    /// Every member the baseline schema names, in the schema's own
    /// order — the byte-for-byte shape the check serves.
    private static let frozenBody = """
        {"bundleId":"22222222-2222-2222-2222-222222222222","bundleVersion":"1.4.2",\
        "url":"https://downloads.example.com/api/v1/downloads/t",\
        "sha256":"aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",\
        "signature":"c2lnYXR1cmU=","keyId":"0123456789abcdef","sizeBytes":256,\
        "forced":false,"minAppVersion":"2.1.0","ingestToken":"sdt1.grant",\
        "pollIntervalSeconds":900}
        """

    func testTheLiteralFrozenShapeParsesWithEveryMembersValue() {
        let manifest = OtaManifest.parse(OtaManifestTests.frozenBody)
        XCTAssertEqual("22222222-2222-2222-2222-222222222222", manifest?.bundleId)
        XCTAssertEqual("1.4.2", manifest?.bundleVersion)
        XCTAssertEqual("https://downloads.example.com/api/v1/downloads/t", manifest?.url)
        XCTAssertEqual("aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899",
                       manifest?.sha256)
        XCTAssertEqual("c2lnYXR1cmU=", manifest?.signature)
        XCTAssertEqual("0123456789abcdef", manifest?.keyId)
        XCTAssertEqual(256, manifest?.sizeBytes)
        XCTAssertEqual(false, manifest?.forced)
        XCTAssertEqual("2.1.0", manifest?.minAppVersion)
        XCTAssertEqual("sdt1.grant", manifest?.ingestToken)
        XCTAssertEqual(900, manifest?.pollIntervalSeconds)
    }

    func testNullOptionalsParseToNilAndAnAbsentForcedDefaultsFalse() {
        // Nulls: the schema's optional members answer nil, not a
        // refusal.
        let nulled = OtaManifest.parse("""
            {"bundleId":"b","bundleVersion":"1","url":"https://x","sha256":"\(String(repeating: "a", count: 64))",\
            "signature":"s","keyId":"k","sizeBytes":null,"forced":true,"minAppVersion":null,\
            "ingestToken":"t","pollIntervalSeconds":60}
            """)
        XCTAssertNil(nulled?.sizeBytes)
        XCTAssertNil(nulled?.minAppVersion)
        XCTAssertEqual(true, nulled?.forced)
        // Absence: `forced` may be omitted entirely (the schema does
        // not require it) and reads as its default false.
        let absentForced = OtaManifest.parse("""
            {"bundleId":"b","bundleVersion":"1","url":"https://x","sha256":"\(String(repeating: "a", count: 64))",\
            "signature":"s","keyId":"k","minAppVersion":"2.0","ingestToken":"t","pollIntervalSeconds":60}
            """)
        XCTAssertEqual(false, absentForced?.forced)
        XCTAssertNil(absentForced?.sizeBytes)
        XCTAssertEqual("2.0", absentForced?.minAppVersion)
    }

    func testAMemberABaselineAddsTomorrowIsToleratedNotFatal() {
        // Forward compatibility: a newer baseline.yaml may ADD members;
        // an older SDK keeps reading the manifest it can understand.
        let extended = """
            {"bundleId":"b","bundleVersion":"1","url":"https://x","sha256":"\(String(repeating: "a", count: 64))",\
            "signature":"s","keyId":"k","ingestToken":"t","pollIntervalSeconds":60,\
            "deltaUrl":"https://x/d","unknownNumber":42,"unknownObject":{"a":[1,2]}}
            """
        XCTAssertEqual("1", OtaManifest.parse(extended)?.bundleVersion)
    }

    func testBodiesThatAreNotTheFrozenObjectAreNil() {
        XCTAssertNil(OtaManifest.parse(""))
        XCTAssertNil(OtaManifest.parse("not json at all"))
        XCTAssertNil(OtaManifest.parse("[\"an\",\"array\"]"))
        XCTAssertNil(OtaManifest.parse("\"just a string\""))
        XCTAssertNil(OtaManifest.parse("null"))
    }

    func testAMissingRequiredMemberIsNil() {
        let strings: [String: String] = [
            "bundleId": "b",
            "bundleVersion": "1",
            "url": "https://x",
            "sha256": String(repeating: "a", count: 64),
            "signature": "s",
            "keyId": "k",
            "ingestToken": "t",
        ]
        func body(dropping member: String) -> String {
            var members = strings.filter { $0.key != member }
                .map { "\"\($0.key)\":\"\($0.value)\"" }
            if member != "pollIntervalSeconds" {
                members.append("\"pollIntervalSeconds\":60")
            }
            return "{" + members.sorted().joined(separator: ",") + "}"
        }
        for required in strings.keys + ["pollIntervalSeconds"] {
            XCTAssertNil(OtaManifest.parse(body(dropping: required)), required)
        }
    }

    func testEmptyRequiredStringsAndWrongTypedMembersAreNil() {
        let emptySha = """
            {"bundleId":"b","bundleVersion":"1","url":"https://x","sha256":"",\
            "signature":"s","keyId":"k","ingestToken":"t","pollIntervalSeconds":60}
            """
        XCTAssertNil(OtaManifest.parse(emptySha))
        // A number where the contract carries a string.
        let numericVersion = """
            {"bundleId":"b","bundleVersion":2,"url":"https://x","sha256":"\(String(repeating: "a", count: 64))",\
            "signature":"s","keyId":"k","ingestToken":"t","pollIntervalSeconds":60}
            """
        XCTAssertNil(OtaManifest.parse(numericVersion))
    }

    func testThePollCadenceIsRefusedUnlessStrictlyPositive() {
        func body(poll: String) -> String {
            """
            {"bundleId":"b","bundleVersion":"1","url":"https://x","sha256":"\(String(repeating: "a", count: 64))",\
            "signature":"s","keyId":"k","ingestToken":"t","pollIntervalSeconds":\(poll)}
            """
        }
        XCTAssertNil(OtaManifest.parse(body(poll: "0")))
        XCTAssertNil(OtaManifest.parse(body(poll: "-60")))
        // A cadence handed as a string is not the contract's int64.
        XCTAssertNil(OtaManifest.parse(body(poll: "\"60\"")))
    }
}

import XCTest
@testable import ShipperDogOta

/// The transport's URL construction contract (issue #175 review, loop
/// 1): every URL the funnel hands the transport is DATA — the config's
/// base walked into the check route, and above all the manifest's own
/// `url` served off the wire — so a malformed value must answer
/// `URLError(.badURL)` at the one construction seam all three verbs
/// share, never the force-unwrap crash the review's high finding
/// caught. The standings those throws walk into are already the
/// updater suite's ground: a failed GET is `transportFailure`, a
/// failed download leg is `downloadFailed` (the Kotlin sibling's own
/// failure answers).
final class OtaHttpTests: XCTestCase {

    func testWellFormedWireUrlsAnswerTheParsedUrl() throws {
        let url = try URLSessionOtaHttp.wireUrl("https://b.example.com/bundles/b-1.tar?x=1")
        XCTAssertEqual("https://b.example.com/bundles/b-1.tar?x=1", url.absoluteString)
    }

    func testMalformedWireUrlsAnswerBadUrlNeverACrash() {
        // The shapes a corrupted or hostile manifest could hand the
        // device, each rejected on EVERY OS the platform floor covers
        // (the guard pins the invariant, not the parser's
        // version-dependent mood): what RFC 3986 cannot parse at all,
        // what the current parser percent-encodes into a scheme-less
        // relative reference, and what parses but speaks a scheme the
        // funnel never uses.
        let malformed = [
            "not a url",
            "example.com/b.tar",
            "https://ex ample.com/b.tar",
            "ht tp://example.com/",
            "https://[bad/x",
            "ftp://example.com/b.tar",
        ]
        for raw in malformed {
            XCTAssertThrowsError(try URLSessionOtaHttp.wireUrl(raw), "input: \(raw)") { error in
                guard let urlError = error as? URLError else {
                    return XCTFail("expected URLError for \(raw), got \(error)")
                }
                XCTAssertEqual(URLError.badURL, urlError.code, "input: \(raw)")
            }
        }
    }
}

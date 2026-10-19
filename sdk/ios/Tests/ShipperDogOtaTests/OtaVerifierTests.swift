import XCTest
import CryptoKit
@testable import ShipperDogOta

/// The frozen crypto contract's proof (issue #175, S16-13; the bytes
/// issue #181's tester pinned against the backend's own
/// `OtaSigningKeyService`):
///
///  - the public key is Base64 of the RAW 32-byte Ed25519 point
///    (CryptoKit's own raw representation — no encoding path at all);
///  - the keyId is the first 16 lowercase hex of the key's SHA-256;
///  - the signature is Ed25519 over the manifest sha256's 64
///    LOWERCASE hex characters, UTF-8 — never over the bundle bytes.
///
/// The suite mints a REAL keypair and signs the way the backend
/// does, then proves the acceptance criterion's own legs: a valid
/// bundle verifies; the tampered-SIGNATURE and tampered-CHECKSUM
/// cases are both rejected, each named for its report word.
final class OtaVerifierTests: XCTestCase {

    private let key = Curve25519.Signing.PrivateKey()

    private var rawPublicKey: Data { key.publicKey.rawRepresentation }

    private var publicKeyBase64: String { rawPublicKey.base64EncodedString() }

    private var keyId: String { OtaVerifier.keyId(ofRaw: rawPublicKey) }

    private let content = Data((0..<300).map { UInt8(truncatingIfNeeded: $0) })

    private var checksumHex: String { OtaVerifier.sha256Hex(of: content) }

    private func signedChecksumHex(_ hex: String = "") -> String {
        let signed = try! key.signature(for: Data(hex.utf8))
        return signed.base64EncodedString()
    }

    private func manifest(sha256: String? = nil,
                          signature: String? = nil,
                          keyId: String? = nil,
                          sizeBytes: Int64? = 300) -> OtaManifest {
        OtaManifest(bundleId: "11111111-1111-1111-1111-111111111111",
                    bundleVersion: "1.4.2",
                    url: "https://downloads.example.com/api/v1/downloads/t",
                    sha256: sha256 ?? checksumHex,
                    signature: signature ?? signedChecksumHex(checksumHex),
                    keyId: keyId ?? self.keyId,
                    sizeBytes: sizeBytes,
                    forced: false,
                    minAppVersion: nil,
                    ingestToken: "sdt1.grant",
                    pollIntervalSeconds: 60)
    }

    private func verifier(_ keys: [String: String] = [:]) -> OtaVerifier {
        OtaVerifier(verificationKeys: keys.isEmpty ? [keyId: publicKeyBase64] : keys)
    }

    private func stagedFile(_ bytes: Data) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("sd-ota-verify-\(UUID().uuidString)", isDirectory: true)
            .appendingPathComponent("bundle")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try bytes.write(to: url)
        return url
    }

    func testTheKeyIdIsTheLowercaseSixteenHexOfTheKeysSha256() {
        XCTAssertEqual(String(OtaVerifier.hexOf(SHA256.hash(data: rawPublicKey)).prefix(16)), keyId)
        XCTAssertEqual(16, keyId.count)
        XCTAssertEqual(keyId, keyId.lowercased())
    }

    func testAValidBundleVerifies() throws {
        let staged = try stagedFile(content)
        let result = verifier().verify(manifest: manifest(), bundle: staged)
        XCTAssertEqual(OtaVerifier.VerificationResult.verified(sha256Hex: checksumHex), result)
    }

    func testTheTamperedSignatureCaseIsRejected() throws {
        let valid = Data(base64Encoded: signedChecksumHex(checksumHex))!
        var tampered = valid
        tampered[0] ^= 0x40
        let staged = try stagedFile(content)
        let result = verifier().verify(manifest: manifest(signature: tampered.base64EncodedString()),
                                       bundle: staged)
        XCTAssertEqual(.signatureInvalid, result)
    }

    func testTheTamperedChecksumCaseIsRejected() throws {
        let staged = try stagedFile(content)
        var flipped = content
        flipped[7] ^= 0x01
        // A staged file whose bytes differ from the manifest label.
        let tamperedFile = try stagedFile(flipped)
        XCTAssertEqual(.checksumMismatch, verifier().verify(manifest: manifest(), bundle: tamperedFile))
        // And the label-side tamper: content matches, label does not.
        let wrongLabel = String(checksumHex.dropLast()) + (checksumHex.hasSuffix("0") ? "1" : "0")
        XCTAssertEqual(.checksumMismatch,
                       verifier().verify(manifest: manifest(sha256: wrongLabel), bundle: staged))
    }

    func testAWrongKeyOrRepointedKeyIdIsRejected() throws {
        let other = Curve25519.Signing.PrivateKey()
        let otherKey = other.publicKey.rawRepresentation.base64EncodedString()
        let staged = try stagedFile(content)
        // The manifest's keyId selects a DIFFERENT key than signed:
        // an unknown key id in the embedded map.
        XCTAssertEqual(.signatureInvalid,
                       verifier([OtaVerifier.keyId(ofRaw: other.publicKey.rawRepresentation): otherKey])
                            .verify(manifest: manifest(), bundle: staged))
        // A repointed keyId: the id names one key, the signature came
        // from another (the map smuggles a key under a foreign id).
        let otherId = OtaVerifier.keyId(ofRaw: other.publicKey.rawRepresentation)
        XCTAssertEqual(.signatureInvalid,
                       verifier([keyId: otherKey, otherId: otherKey])
                            .verify(manifest: manifest(keyId: otherId), bundle: staged))
    }

    func testASizeMismatchIsRejectedBeforeHashing() {
        XCTAssertEqual(.checksumMismatch,
                       verifier().verify(manifest: manifest(sizeBytes: 301), bytes: content))
    }

    func testTheChecksumContractIsLowercaseHexOverTheBytesNotTheLabel() {
        XCTAssertEqual(64, checksumHex.count)
        XCTAssertEqual(checksumHex, checksumHex.lowercased())
        XCTAssertEqual(.verified(sha256Hex: checksumHex),
                       verifier().verify(manifest: manifest(), bytes: content))
    }

    func testTheBase64OverloadDerivesTheSameKeyId() {
        XCTAssertEqual(keyId, OtaVerifier.keyId(ofPublicKeyBase64: publicKeyBase64))
        XCTAssertNil(OtaVerifier.keyId(ofPublicKeyBase64: Data(repeating: 1, count: 31).base64EncodedString()))
        XCTAssertNil(OtaVerifier.keyId(ofPublicKeyBase64: "not-base64"))
    }

    func testUppercasedLabelsAndKeyIdsNormalizeToTheFrozenContract() throws {
        // The wire may hand back uppercase hex; the content leg and
        // the keyId lookup normalize, and the signature stays over
        // the LOWERCASE digest the backend signed.
        let staged = try stagedFile(content)
        let upper = verifier().verify(manifest: manifest(sha256: checksumHex.uppercased(),
                                                         keyId: keyId.uppercased()),
                                      bundle: staged)
        XCTAssertEqual(.verified(sha256Hex: checksumHex), upper)
    }

    func testAGarbageSignatureIsTheInvalidLegNeverACrash() throws {
        let staged = try stagedFile(content)
        XCTAssertEqual(.signatureInvalid,
                       verifier().verify(manifest: manifest(signature: "not-base64-at-all"), bundle: staged))
    }

    func testTheX509WrappedKeyIsNotTheFrozenShape() {
        // The backend's own warning: JCA's getEncoded() answers the
        // 44-byte X.509 SubjectPublicKeyInfo wrapping (RFC 8410's
        // fixed 12-byte DER header before the raw point); the frozen
        // contract is the RAW 32 bytes. A wrapped key embedded by
        // mistake is refused by shape, before any signature leg runs.
        let derHeader = Data([0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00])
        let wrapped = derHeader + rawPublicKey
        XCTAssertEqual(44, wrapped.count)
        XCTAssertNil(OtaVerifier.keyId(ofPublicKeyBase64: wrapped.base64EncodedString()))
        XCTAssertEqual(keyId, OtaVerifier.keyId(ofPublicKeyBase64: publicKeyBase64))
    }

    func testUnpaddedBase64SignaturesDecodeTheWayTheWireMayOmitPadding() throws {
        let unpadded = signedChecksumHex(checksumHex)
            .replacingOccurrences(of: "=", with: "")
        let staged = try stagedFile(content)
        // The 64-byte Ed25519 signature is 88 base64 characters with
        // its "==" tail; the decoder re-pads whichever form arrives.
        let result = verifier().verify(manifest: manifest(signature: unpadded), bundle: staged)
        XCTAssertEqual(.verified(sha256Hex: checksumHex), result)
    }
}

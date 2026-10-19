import Foundation
import CryptoKit

/// The OTA bundle verifier (issue #175, S16-13; ADR-0010 D8's frozen
/// crypto contract, pinned exactly as the backend's
/// `OtaSigningKeyService` shapes it — the bytes issue #181's tester
/// proved):
///
///  - `publicKey` is Base64 of the RAW 32-byte Ed25519 public key;
///  - `keyId` is the first 16 LOWERCASE hex characters of the
///    SHA-256 of that raw key (re-derived here as a pin — a manifest
///    cannot re-point a key id at another key);
///  - `signature` is Base64 of the 64-byte Ed25519 signature over the
///    manifest sha256's 64 LOWERCASE hex characters, UTF-8 — never
///    over the bundle bytes themselves.
///
/// CryptoKit only, the ADR-0009 doctrine: no hand-rolled signature
/// paths, not even key encodings — the raw 32-byte point IS
/// `Curve25519.Signing.PublicKey`'s own raw representation.
/// Verification is two independent legs: the content hash (the staged
/// file's sha256 must equal the manifest's label) and the label's
/// Ed25519 signature under the embedded key the manifest's keyId
/// selects. The failure outcomes name the leg that refused, in the
/// server's own report vocabulary (checksum_mismatch,
/// signature_invalid).
public final class OtaVerifier {

    /// The verification legs' outcomes, in the ingest contract's own
    /// report vocabulary.
    public enum VerificationResult: Equatable {
        case verified(sha256Hex: String)
        case checksumMismatch
        case signatureInvalid
    }

    private final class VerifiedKey {
        let raw: Data
        let key: Curve25519.Signing.PublicKey?

        init(raw: Data) {
            self.raw = raw
            self.key = try? Curve25519.Signing.PublicKey(rawRepresentation: raw)
        }
    }

    private let keys: [String: VerifiedKey]

    public init(verificationKeys: [String: String]) {
        var map: [String: VerifiedKey] = [:]
        for (keyId, publicKeyBase64) in verificationKeys {
            guard let raw = OtaVerifier.base64Decoded(publicKeyBase64),
                  raw.count == OtaConfig.ed25519KeyBytes else {
                preconditionFailure("a verification key is Base64 of \(OtaConfig.ed25519KeyBytes) raw bytes")
            }
            map[keyId.lowercased()] = VerifiedKey(raw: raw)
        }
        self.keys = map
    }

    /// Verifies the staged bundle file (streams the hash in chunks —
    /// a bundle never sits in memory).
    public func verify(manifest: OtaManifest, bundle: URL) -> VerificationResult {
        let attributes = try? FileManager.default.attributesOfItem(atPath: bundle.path)
        let length = (attributes?[.size] as? NSNumber)?.int64Value
        guard let stream = try? FileHandle(forReadingFrom: bundle) else {
            return .checksumMismatch
        }
        defer { try? stream.close() }
        return verify(manifest: manifest, bytes: stream, length: length)
    }

    /// The in-memory shape (the tests' small fixtures).
    func verify(manifest: OtaManifest, bytes: Data) -> VerificationResult {
        verify(manifest: manifest, sha256Hex: OtaVerifier.sha256Hex(of: bytes), length: Int64(bytes.count))
    }

    /// The streaming shape: hashes to EOF, then checks the signature leg.
    func verify(manifest: OtaManifest, bytes: FileHandle, length: Int64?) -> VerificationResult {
        verify(manifest: manifest, sha256Hex: OtaVerifier.sha256Hex(of: bytes), length: length)
    }

    private func verify(manifest: OtaManifest, sha256Hex actualHex: String, length: Int64?) -> VerificationResult {
        if let length, let sizeBytes = manifest.sizeBytes, length != sizeBytes {
            return .checksumMismatch
        }
        let expectedHex = manifest.sha256.lowercased()
        guard expectedHex == actualHex else {
            return .checksumMismatch
        }
        let id = manifest.keyId.lowercased()
        guard let key = keys[id] else {
            return .signatureInvalid
        }
        // The pin: the selected key's own derived id must equal the
        // id that selected it (a map entry cannot smuggle a different
        // key under a known id).
        guard OtaVerifier.keyId(ofRaw: key.raw) == id else {
            return .signatureInvalid
        }
        guard let cryptoKey = key.key,
              let signature = OtaVerifier.base64Decoded(manifest.signature) else {
            return .signatureInvalid
        }
        // The signature is over the LOWERCASE hex label, UTF-8 —
        // never over the bundle bytes themselves.
        guard cryptoKey.isValidSignature(signature, for: Data(actualHex.utf8)) else {
            return .signatureInvalid
        }
        return .verified(sha256Hex: actualHex)
    }

    /// The key id of a raw public key: 16 lowercase hex of its
    /// SHA-256 (the contract's own derivation).
    public static func keyId(ofRaw rawPublicKey: Data) -> String {
        String(OtaVerifier.hexOf(SHA256.hash(data: rawPublicKey)).prefix(OtaConfig.keyIdLength))
    }

    /// The Base64 overload of the derivation (the console's own
    /// copyable form); nil when the value is not raw-32-byte Base64.
    public static func keyId(ofPublicKeyBase64 publicKeyBase64: String) -> String? {
        guard let raw = base64Decoded(publicKeyBase64), raw.count == OtaConfig.ed25519KeyBytes else {
            return nil
        }
        return keyId(ofRaw: raw)
    }

    /// The manifest sha256's own shape: 64 lowercase hex characters,
    /// streamed in chunks.
    public static func sha256Hex(of bytes: FileHandle) -> String {
        var hasher = SHA256()
        while true {
            let chunk = bytes.readData(ofLength: hashChunkBytes)
            if chunk.isEmpty {
                break
            }
            hasher.update(data: chunk)
        }
        return hexOf(hasher.finalize())
    }

    public static func sha256Hex(of bytes: Data) -> String {
        hexOf(SHA256.hash(data: bytes))
    }

    public static func hexOf<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Standard Base64 with the padding the wire may omit.
    static func base64Decoded(_ value: String) -> Data? {
        var padded = value
        let remainder = padded.count % 4
        if remainder != 0 {
            padded += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: padded)
    }

    private static let hashChunkBytes = 65_536
}

import Foundation

/// The OTA manifest the check serves (issue #175, S16-13; the frozen
/// wire shape of docs/openapi/baseline.yaml's `Manifest` — camelCase
/// members, byte-for-byte the backend record): the bundle's identity
/// and content facts, the minted download URL (D5) and `sdt1.` ingest
/// grant (D4), the effective forced/minAppVersion overrides (D7), and
/// the server-owned poll cadence (D9).
///
/// The parser is failable by design: the manifest is WIRE data, so a
/// malformed body answers nil (the updater's protocol-failure
/// standing) — never a crash on anything the server handed back.
public struct OtaManifest: Equatable {

    public let bundleId: String
    public let bundleVersion: String
    public let url: String
    public let sha256: String
    public let signature: String
    public let keyId: String
    public let sizeBytes: Int64?
    public let forced: Bool
    public let minAppVersion: String?
    public let ingestToken: String
    public let pollIntervalSeconds: Int64

    init(bundleId: String,
         bundleVersion: String,
         url: String,
         sha256: String,
         signature: String,
         keyId: String,
         sizeBytes: Int64?,
         forced: Bool,
         minAppVersion: String?,
         ingestToken: String,
         pollIntervalSeconds: Int64) {
        self.bundleId = bundleId
        self.bundleVersion = bundleVersion
        self.url = url
        self.sha256 = sha256
        self.signature = signature
        self.keyId = keyId
        self.sizeBytes = sizeBytes
        self.forced = forced
        self.minAppVersion = minAppVersion
        self.ingestToken = ingestToken
        self.pollIntervalSeconds = pollIntervalSeconds
    }

    /// Parses one check response body; nil when the body is not the
    /// frozen shape. Null members stay nil; an ABSENT `forced` flag
    /// forces the default false (never an optional flag); a member
    /// the contract requires but the wire omitted reads as invalid.
    public static func parse(_ body: String) -> OtaManifest? {
        guard let data = body.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            return nil
        }
        guard let bundleId = object["bundleId"] as? String, !bundleId.isEmpty,
              let bundleVersion = object["bundleVersion"] as? String, !bundleVersion.isEmpty,
              let url = object["url"] as? String, !url.isEmpty,
              let sha256 = object["sha256"] as? String, !sha256.isEmpty,
              let signature = object["signature"] as? String, !signature.isEmpty,
              let keyId = object["keyId"] as? String, !keyId.isEmpty,
              let ingestToken = object["ingestToken"] as? String, !ingestToken.isEmpty,
              let pollIntervalSeconds = object["pollIntervalSeconds"] as? Int64,
              pollIntervalSeconds > 0 else {
            return nil
        }
        return OtaManifest(
            bundleId: bundleId,
            bundleVersion: bundleVersion,
            url: url,
            sha256: sha256,
            signature: signature,
            keyId: keyId,
            sizeBytes: object["sizeBytes"] as? Int64,
            forced: (object["forced"] as? Bool) ?? false,
            minAppVersion: object["minAppVersion"] as? String,
            ingestToken: ingestToken,
            pollIntervalSeconds: pollIntervalSeconds)
    }
}

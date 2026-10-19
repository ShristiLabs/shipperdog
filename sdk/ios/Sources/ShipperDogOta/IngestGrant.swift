import Foundation

/// The ingest grant carried by the manifest's `ingestToken` (issue
/// #175, S16-13; ADR-0010 D4): the check mints an `sdt1.` telemetry
/// token whose payload names the reporting tenant and the release the
/// lifecycle events must carry — the two identifiers the frozen
/// `Manifest` wire shape deliberately does not repeat, because the
/// grant IS their carrier.
///
/// The token is HMAC-signed, not encrypted: the SDK reads the payload
/// blocks (integrity stays the server's to enforce — a tampered token
/// simply fails the ingest's own authentication) and never verifies
/// or mints anything. This is reading the grant the platform handed
/// the device, not parsing the customer's credentials; the payload is
/// `1|tenantId|testerUserId|releaseId|deviceId|expiryEpochSecond`,
/// each id a UUID, base64url-encoded — the exact blocks the backend
/// codec joins (the `TelemetryTokenCodec` doctrine).
struct IngestGrant: Equatable {

    let tenantId: UUID
    let releaseId: UUID
    let deviceId: UUID?

    /// The frozen ingest route (docs/analytics.md):
    /// `{base}/api/v1/tenants/{tenantId}/analytics/events`.
    func ingestUrl(baseUrl: String) -> String {
        baseUrl.trimmingTrailingSlashes() + "/api/v1/tenants/\(tenantId.uuidString.lowercased())/analytics/events"
    }

    /// The frozen token family's prefix.
    static let ingestTokenPrefix = "sdt1."

    /// The payload's own version block.
    private static let payloadVersion = "1"

    /// `1|tenantId|testerUserId|releaseId|deviceId|expiry` — six blocks.
    private static let payloadFields = 6

    /// Reads the grant a manifest served; nil when the token is not a
    /// well-formed `sdt1.` grant (the reporter runs unbound, the
    /// funnel never blocks on a bad grant).
    static func parse(_ token: String?) -> IngestGrant? {
        guard let token, token.hasPrefix(ingestTokenPrefix) else {
            return nil
        }
        let body = String(token.dropFirst(ingestTokenPrefix.count))
        guard let separator = body.firstIndex(of: "."), separator > body.startIndex else {
            return nil
        }
        guard let payloadData = base64UrlDecoded(String(body[body.startIndex..<separator])),
              let payload = String(data: payloadData, encoding: .utf8) else {
            return nil
        }
        let fields = payload.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard fields.count == payloadFields, fields[0] == payloadVersion else {
            return nil
        }
        guard let tenantId = UUID(uuidString: fields[1]),
              let releaseId = UUID(uuidString: fields[3]) else {
            return nil
        }
        let deviceId = fields[4].isEmpty ? nil : UUID(uuidString: fields[4])
        if !fields[4].isEmpty, deviceId == nil {
            return nil
        }
        return IngestGrant(tenantId: tenantId, releaseId: releaseId, deviceId: deviceId)
    }

    /// Base64url (RFC 4648 section 5, unpadded on the wire) decoded
    /// with Foundation's standard alphabet after translation.
    private static func base64UrlDecoded(_ value: String) -> Data? {
        let standard = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        var padded = standard
        let remainder = padded.count % 4
        if remainder != 0 {
            padded += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: padded)
    }
}

import Foundation

/// The OTA updater SDK's configuration (issue #175, S16-13; ADR-0010
/// workstream 9): one value object carrying everything the check,
/// download, verify, and report funnel needs — the shipped app's
/// `sdc1.` app token (D3: the fleet's embedded identifier, minted by
/// the console/CLI), the device's own report vocabulary (the check
/// query's members, bounded exactly as the check surface validates
/// them), the embedded Ed25519 verification keys (D8: the public keys
/// copyable in the console, keyed by their 16-hex keyId), and the
/// server-cadence fallback used before the first manifest arrives.
///
/// The poll loop is host-driven — the telemetry beacon's own doctrine:
/// no timers, no services, no background threads in the core. The
/// manifest's `pollIntervalSeconds` (D9: server-owned cadence) is the
/// loop's answer for when to check next; the host schedules the call
/// (the demo shows the complete re-arming loop).
public struct OtaConfig: Equatable {

    /// The platform's API base (scheme + host, no path) — the check
    /// URL and the lifecycle ingest URL derive from it.
    public let baseUrl: String

    /// The application's `sdc1.` app token (the check's only
    /// credential; identifier, not secret — D3).
    public let appToken: String

    /// The device's own raw identifier (the rollout bucketing's
    /// input; at most 128 characters, S16-07's bound).
    public let deviceId: String

    /// The device's platform tag (lowercased on the wire).
    public let platform: String

    /// The app version it runs.
    public let appVersion: String

    /// The OTA channel (the ring vocabulary).
    public let channel: String

    /// The OPTIONAL bundle version label the device already carries —
    /// absent on a first check, present afterwards (D2: the
    /// up-to-date comparison's input).
    public let bundleVersion: String?

    /// The cadence fallback before the first manifest names the
    /// server's own (D9).
    public let pollIntervalSeconds: Int64

    /// The embedded public keys by keyId: values are Base64 of the
    /// RAW 32-byte Ed25519 public key (D8's frozen crypto contract;
    /// the keyId is 16 lowercase hex of the key's SHA-256 and is
    /// re-derived at verification as a pin).
    public let verificationKeys: [String: String]

    /// Where the offline lifecycle-event queue persists; nil keeps
    /// events in memory.
    public let queueDirectory: URL?

    /// Where bundle bytes stage (the `.part` resumable file and the
    /// verified bundle); nil stages in a fresh temporary directory
    /// per downloader (a real app supplies its own storage).
    public let downloadDirectory: URL?

    /// The offline queue's bound: oldest dropped first (the telemetry
    /// queue's own bounded-disk discipline).
    public let maxQueueEvents: Int

    public init(
        baseUrl: String,
        appToken: String,
        deviceId: String,
        platform: String,
        appVersion: String,
        channel: String,
        bundleVersion: String? = nil,
        pollIntervalSeconds: Int64 = OtaConfig.defaultPollIntervalSeconds,
        verificationKeys: [String: String] = [:],
        queueDirectory: URL? = nil,
        downloadDirectory: URL? = nil,
        maxQueueEvents: Int = OtaConfig.defaultMaxQueueEvents
    ) {
        precondition(!baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "baseUrl is required")
        precondition(appToken.hasPrefix(OtaConfig.appTokenPrefix),
                     "appToken must start with \(OtaConfig.appTokenPrefix)")
        precondition(!deviceId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                     "deviceId is required")
        precondition(deviceId.count <= OtaConfig.maxDeviceIdChars,
                     "deviceId is at most \(OtaConfig.maxDeviceIdChars) characters")
        precondition(!platform.isEmpty, "platform is required")
        precondition(platform.count <= OtaConfig.maxPlatformChars,
                     "platform is at most \(OtaConfig.maxPlatformChars) characters")
        precondition(!appVersion.isEmpty, "appVersion is required")
        precondition(appVersion.count <= OtaConfig.maxAppVersionChars,
                     "appVersion is at most \(OtaConfig.maxAppVersionChars) characters")
        precondition(!channel.isEmpty, "channel is required")
        precondition(channel.count <= OtaConfig.maxChannelChars,
                     "channel is at most \(OtaConfig.maxChannelChars) characters")
        if let bundleVersion = bundleVersion {
            precondition(!bundleVersion.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                         "bundleVersion is blank or a label")
            precondition(bundleVersion.count <= OtaConfig.maxBundleVersionChars,
                         "bundleVersion is at most \(OtaConfig.maxBundleVersionChars) characters")
        }
        precondition(pollIntervalSeconds > 0, "pollIntervalSeconds must be positive")
        precondition(maxQueueEvents > 0, "maxQueueEvents must be positive")
        for (keyId, publicKeyBase64) in verificationKeys {
            precondition(keyId.count == OtaConfig.keyIdLength,
                         "a verification key id is \(OtaConfig.keyIdLength) hex characters")
            guard let raw = OtaVerifier.base64Decoded(publicKeyBase64) else {
                preconditionFailure("a verification key is Base64 of raw bytes")
            }
            precondition(raw.count == OtaConfig.ed25519KeyBytes,
                         "a verification key is Base64 of \(OtaConfig.ed25519KeyBytes) raw bytes")
        }
        self.baseUrl = baseUrl
        self.appToken = appToken
        self.deviceId = deviceId
        self.platform = platform
        self.appVersion = appVersion
        self.channel = channel
        self.bundleVersion = bundleVersion
        self.pollIntervalSeconds = pollIntervalSeconds
        self.verificationKeys = verificationKeys
        self.queueDirectory = queueDirectory
        self.downloadDirectory = downloadDirectory
        self.maxQueueEvents = maxQueueEvents
    }

    /// The frozen check route (docs/openapi/baseline.yaml, GET /api/v1/ota/check).
    public var checkUrl: String {
        baseUrl.trimmingTrailingSlashes() + "/api/v1/ota/check"
    }

    /// The check query with every member the device reports,
    /// percent-encoded once and normalized exactly as the check
    /// surface validates it.
    var checkQuery: String {
        var members: [String] = []
        members.append("deviceId=" + OtaConfig.encode(deviceId.trimmingCharacters(in: .whitespacesAndNewlines)))
        members.append("platform=" + OtaConfig.encode(platform.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()))
        members.append("appVersion=" + OtaConfig.encode(appVersion.trimmingCharacters(in: .whitespacesAndNewlines)))
        if let label = bundleVersion?.trimmingCharacters(in: .whitespacesAndNewlines), !label.isEmpty {
            members.append("bundleVersion=" + OtaConfig.encode(label))
        }
        members.append("channel=" + OtaConfig.encode(channel.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()))
        return members.joined(separator: "&")
    }

    /// The `sdc1.` fleet credential's frozen prefix (D3).
    public static let appTokenPrefix = "sdc1."

    /// The check surface's own field bounds (S16-07), mirrored so
    /// config fails before the network does.
    public static let maxDeviceIdChars = 128
    public static let maxPlatformChars = 64
    public static let maxAppVersionChars = 64
    public static let maxChannelChars = 64
    public static let maxBundleVersionChars = 128

    /// D8's frozen key shapes.
    public static let ed25519KeyBytes = 32
    public static let keyIdLength = 16

    /// The cadence fallback until the first manifest names the
    /// server's own (D9).
    public static let defaultPollIntervalSeconds: Int64 = 3_600

    public static let defaultMaxQueueEvents = 200

    /// RFC 3986's unreserved set — the telemetry SDK's own encoding
    /// alphabet (the feedback deep link's hint uses the same).
    private static let queryAllowed: CharacterSet =
        CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))

    private static func encode(_ value: String) -> String {
        value.addingPercentEncoding(withAllowedCharacters: queryAllowed) ?? ""
    }
}

extension String {
    func trimmingTrailingSlashes() -> String {
        var value = self
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }
}

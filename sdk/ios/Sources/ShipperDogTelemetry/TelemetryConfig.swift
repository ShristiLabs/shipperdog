import Foundation

/// The tester telemetry SDK's configuration (issue #72, S15-01): one
/// value built from the telemetry block the portal download issuance
/// answers with — the token is the ONLY identity the SDK holds or
/// sends (no user id, no email, no free-text field exists in the
/// payload; the privacy posture is structural).
public struct TelemetryConfig: Equatable {

    /// The platform's API base (scheme + host, no path); the ingest
    /// URL derives from it.
    public let baseUrl: String

    /// The telemetry token the download minted (ingest-only by
    /// construction server-side).
    public let token: String

    /// The reporting tenant of the download.
    public let tenantId: String

    /// The application of the release (the feedback deep link's
    /// addressing).
    public let applicationId: String

    /// The release the token reports for (every event names it; the
    /// server enforces the binding).
    public let releaseId: String

    /// The optional pairing with one of the tenant's REGISTERED
    /// devices (the registry's id — never a UDID or serial).
    public let deviceId: String?

    /// Where the offline queue and one-shot markers persist (nil
    /// keeps everything in memory).
    public let queueDirectory: URL?

    /// The offline queue's bound; the oldest events drop first.
    public let maxQueueEvents: Int

    public init(
        baseUrl: String,
        token: String,
        tenantId: String,
        applicationId: String,
        releaseId: String,
        deviceId: String? = nil,
        queueDirectory: URL? = nil,
        maxQueueEvents: Int = TelemetryConfig.defaultMaxQueueEvents
    ) {
        precondition(!baseUrl.isEmpty && !token.isEmpty && !tenantId.isEmpty && !releaseId.isEmpty,
                     "baseUrl, token, tenantId, and releaseId are required")
        precondition(maxQueueEvents > 0, "maxQueueEvents must be positive")
        self.baseUrl = baseUrl
        self.token = token
        self.tenantId = tenantId
        self.applicationId = applicationId
        self.releaseId = releaseId
        self.deviceId = deviceId
        self.queueDirectory = queueDirectory
        self.maxQueueEvents = maxQueueEvents
    }

    /// The frozen events contract's ingest route (docs/analytics.md).
    public var ingestUrl: String {
        "\(baseUrl.trimmingSlashes())/api/v1/tenants/\(tenantId)/analytics/events"
    }

    /// The tester portal's version deep link (the feedback handoff).
    public func portalVersionUrl(portalBaseUrl: String) -> String {
        "\(portalBaseUrl.trimmingSlashes())/applications/\(tenantId)/\(applicationId)"
    }

    public static let defaultMaxQueueEvents = 2_000
    public static let maxBatchEvents = 500
    public static let maxPrefillChars = 2_000
}

extension String {
    fileprivate func trimmingSlashes() -> String {
        var value = self
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }
}

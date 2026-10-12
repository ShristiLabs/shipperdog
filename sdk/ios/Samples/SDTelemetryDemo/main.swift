import Foundation
import ShipperDogTelemetry

// The SDK's complete iOS integration (issue #72, S15-01, AC1): the
// config value (pasted from the portal download answer's telemetry
// block) and ONE start call — inside a real app both live in
// `application(_:didFinishLaunchingWithOptions:)` and the session
// wiring registers its own observers:
//
//     func application(_ application: UIApplication,
//                      didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
//         let sdk = ShipperDogTelemetry.start(config: TelemetryConfig(
//             baseUrl: "https://shipperdog.example.com",
//             token: telemetryBlock.token,
//             tenantId: telemetryBlock.tenantId,
//             applicationId: telemetryBlock.applicationId,
//             releaseId: telemetryBlock.releaseId,
//             queueDirectory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
//                 .map { $0.appendingPathComponent("shipperdog-telemetry", isDirectory: true) }))
//         CrashReporter.install(sdk: sdk)
//         sdk.installSessionTracking()
//         return true
//     }
//
// This demo exercises the same flow on macOS (the UIKit wiring is
// compiled by the iOS-destination build): start, first launch, one
// session, and a flush against a stub transport surface.
let config = TelemetryConfig(
    baseUrl: "https://shipperdog.example.com",
    token: "sdt1.paste-from-the-download-answer",
    tenantId: "00000000-0000-0000-0000-000000000001",
    applicationId: "00000000-0000-0000-0000-000000000002",
    releaseId: "00000000-0000-0000-0000-000000000003",
    queueDirectory: FileManager.default.temporaryDirectory
        .appendingPathComponent("sd-demo-\(UUID().uuidString)", isDirectory: true))

let sdk = ShipperDogTelemetry.start(config: config)
sdk.onFirstLaunch()
sdk.onSessionStart()
sdk.onSessionEnd()
sdk.foldPendingCrashMarker()
print("queue depth: \(sdk.pendingEvents)")
print("feedback: \(sdk.feedbackUrl(portalBaseUrl: "https://portal.example.com"))")
sdk.optOut()
print("after opt-out: \(sdk.pendingEvents)")

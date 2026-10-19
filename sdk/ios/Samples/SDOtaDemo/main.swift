import Foundation
import ShipperDogOta

// The OTA updater's complete iOS integration (issue #175, S16-13):
// the config value (the fleet's `sdc1.` app token plus the app's
// embedded verification keys) and ONE start call — inside a real app
// both live in `application(_:didFinishLaunchingWithOptions:)` beside
// the telemetry beacon, and the host-owned poll loop re-arms itself
// unconditionally (only the host's own stop halts it — the #174
// review's liveness lesson, wired here from the start):
//
//     func application(_ application: UIApplication,
//                      didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
//         let updater = OtaUpdater.start(config: OtaConfig(
//             baseUrl: "https://shipperdog.example.com",
//             appToken: "sdc1.paste-the-fleet-token-the-console-issued",
//             deviceId: UIDevice.current.identifierForVendor?.uuidString ?? "the-device-own-identifier",
//             platform: "ios",
//             appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.0.0",
//             channel: "ota",
//             verificationKeys: [
//                 // The application's ACTIVE signing key: Base64 of the
//                 // raw 32-byte Ed25519 public key, copyable in the console.
//                 "0123456789abcdef": "paste-the-base64-public-key",
//             ],
//             queueDirectory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
//                 .map { $0.appendingPathComponent("shipperdog-ota", isDirectory: true) },
//             downloadDirectory: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
//                 .map { $0.appendingPathComponent("shipperdog-ota-bundles", isDirectory: true) }))
//         startPollLoop(updater, applier: BundleSwapper())
//         return true
//     }
//
//     private func startPollLoop(_ updater: OtaUpdater, applier: BundleApplier) {
//         let queue = DispatchQueue(label: "shipperdog.ota.poll")
//         var timer: DispatchSourceTimer?
//         timer = DispatchSource.makeTimerSource(queue: queue)
//         timer?.schedule(deadline: .now())
//         timer?.setEventHandler {
//             updater.updateNow(applier: applier) // throws never: the funnel answers standings
//             timer?.schedule(deadline: .now() + .seconds(Int(updater.nextPollSeconds())))
//         }
//         timer?.resume()
//     }
//
// This demo exercises the same surfaces on macOS WITHOUT network I/O
// (CI builds the demo but never runs it; the funnel itself is the
// XCTest suite's ground): the config, the one start call, and the
// host-visible state the loop reads.
//
// The sdk-ci size budget prices THIS executable's linked, stripped
// delta (per library), so the demo must reference the COMPLETE
// funnel — check, download, verify, apply — not just the start call:
// unmentioned methods dead-strip out of the release binary and the
// budget would under-measure the very legs (downloader, verifier,
// applier seam) the SDK ships. Function values and the applier
// conformance below are compile-time references only; nothing here
// touches the network.
final class DemoApplier: BundleApplier {

    /// The sample's stand-in for the app-supplied apply step: a real
    /// app swaps in its own mechanics (the iOS note above); the demo
    /// answers the considered refusal and stays offline.
    func apply(_ manifest: OtaManifest, bundle: URL) throws -> ApplyResult {
        .refused
    }
}

let config = OtaConfig(
    baseUrl: "https://shipperdog.example.com",
    appToken: "sdc1.paste-the-fleet-token-the-console-issued",
    deviceId: "the-device-own-identifier",
    platform: "ios",
    appVersion: "2.0.0",
    channel: "ota",
    verificationKeys: [
        // A well-formed placeholder (Base64 of 32 raw bytes) so the
        // demo runs; a real app pastes the console's ACTIVE key.
        "0123456789abcdef": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    ])

let updater = OtaUpdater.start(config: config)
let applier = DemoApplier()
let check = updater.checkNow
let update = updater.updateNow(applier:)
print("funnel linked for the size budget: check \(String(describing: type(of: check))), update(applier:) \(String(describing: type(of: update))), applier \(String(describing: type(of: applier)))")
print("check url: \(config.checkUrl)?deviceId=the-device-own-identifier&platform=ios&appVersion=2.0.0&channel=ota")
print("poll cadence: \(updater.nextPollSeconds())s (the config fallback until a manifest names its own)")
print("manifest in hand: \(updater.currentManifest.map(\.bundleVersion) ?? "none")")
print("pending reports: \(updater.pendingEvents)")
updater.reportRolledBack(detail: "demo-rollback-word")
print("after the unbound rollback report: \(updater.pendingEvents) (no manifest served, nothing to report yet)")

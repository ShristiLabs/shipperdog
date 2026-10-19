// swift-tools-version:5.9
// The tester telemetry SDK's iOS package (issue #72, S15-01, PRD 37;
// the OTA updater module since issue #175, S16-13): library targets
// of pure Foundation plus CryptoKit — zero external dependencies
// (the < 200 KB budget is structural), URLSession for the transport,
// NSSetUncaughtExceptionHandler chaining for crash context, and the
// UIKit session wiring guarded by canImport(UIKit) so the package
// builds and tests on macOS while shipping the iOS paths. The
// platform floor (macOS 10.15 / iOS 13) is CryptoKit's own — the
// Ed25519/sha256 verification legs have no hand-rolled fallback by
// doctrine (ADR-0009). The demo executables are the docs-proven
// samples: the complete integration is the config value and ONE
// start call.
import PackageDescription

let package = Package(
    name: "ShipperDogTelemetry",
    platforms: [
        .macOS(.v10_15),
        .iOS(.v13),
    ],
    targets: [
        .target(
            name: "ShipperDogTelemetry",
            path: "Sources/ShipperDogTelemetry"
        ),
        .testTarget(
            name: "ShipperDogTelemetryTests",
            dependencies: ["ShipperDogTelemetry"],
            path: "Tests/ShipperDogTelemetryTests"
        ),
        .executableTarget(
            name: "SDTelemetryDemo",
            dependencies: ["ShipperDogTelemetry"],
            path: "Samples/SDTelemetryDemo"
        ),
        .target(
            name: "ShipperDogOta",
            path: "Sources/ShipperDogOta"
        ),
        .testTarget(
            name: "ShipperDogOtaTests",
            dependencies: ["ShipperDogOta"],
            path: "Tests/ShipperDogOtaTests"
        ),
        .executableTarget(
            name: "SDOtaDemo",
            dependencies: ["ShipperDogOta"],
            path: "Samples/SDOtaDemo"
        ),
    ]
)

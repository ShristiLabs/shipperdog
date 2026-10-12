// swift-tools-version:5.9
// The tester telemetry SDK's iOS package (issue #72, S15-01, PRD 37):
// one library target of pure Foundation — zero external dependencies
// (the < 200 KB budget is structural), URLSession for the transport,
// NSSetUncaughtExceptionHandler chaining for crash context, and the
// UIKit session wiring guarded by canImport(UIKit) so the package
// builds and tests on macOS while shipping the iOS paths. The demo
// executable is the docs-proven sample (AC1): the complete
// integration is the config value and ONE start call.
import PackageDescription

let package = Package(
    name: "ShipperDogTelemetry",
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
    ]
)

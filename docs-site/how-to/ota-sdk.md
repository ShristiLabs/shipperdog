---
description: Integrate the OTA SDK - wire the Kotlin or Swift updater into your app with the embedded signing key, the host-driven check loop, your apply callback, and automatic lifecycle reporting.
---

# Integrate the OTA SDK

Your app checks ShipperDog for OTA updates through a small SDK — a
pure Kotlin/JVM core with a thin Android adapter, and a Swift library
with zero external dependencies. One start call, one callback you
write, and the SDK walks the whole funnel: check, resumable download,
signature verification, apply, lifecycle reporting. The model —
manifests, signing, staged rollout — is
[OTA updates](/concepts/ota-updates.md); this page is the wiring.

## Before you start

You need three things from the console, all covered by
[manage OTA bundles](/how-to/console/ota-bundles.md):

1. **OTA enabled** for the application — the check refuses otherwise.
2. **The signing key pair's public half** — after the application's
   first finalized bundle generates it, copy the `keyId` and the
   Base64 public key from the application's OTA block, and embed both
   in your app (the config's `keyId`-to-key map).
3. **A `sdc1.` app token** — issued once per application; copy it when
   the dialog shows it, because it is never shown again. It is an
   identifier, not a secret.

Platform floors: the Android SDK is pure Kotlin/JVM with zero runtime
dependencies — every API it touches is JDK API Android ships; the
Swift library needs macOS 10.15 / iOS 13 or later (CryptoKit's own
floor). Size is CI-asserted: the two OTA jars total about 74 KB of a
combined 200 KB SDK budget on Android; the Swift library's
linked-and-stripped contribution is about 133 KB.

## Add the SDK (Kotlin)

The Android SDK ships as source modules in the repo — no Maven
artifact. Copy `sdk/android/ota` (the core) and
`sdk/android/ota-android` (the adapter) into your Gradle build, or
reference the cloned repo as a composite build:

```kotlin
// settings.gradle.kts
include(":ota")          // pure Kotlin/JVM core - zero runtime dependencies
include(":ota-android")  // thin adapter: storage wiring + the poll loop

// app/build.gradle.kts
dependencies {
    implementation(project(":ota-android")) // api-includes the :ota core
}
```

## Add the SDK (Swift)

The Swift package lives at `sdk/ios` in the repo — there is no
`Package.swift` at the repo root, so a plain URL add will not resolve.
Clone the repo, add `sdk/ios` as a local package
(File > Add Packages > Add Local), and link the `ShipperDogOta`
product to your app target: Foundation and CryptoKit only, zero
external dependencies.

## Configure and start (Kotlin)

The complete integration, proven compiling in `sdk/android/sample` —
the config value, one start call in `Application.onCreate`, and your
apply callback:

```kotlin
class DemoApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val ota = OtaConfig(
            baseUrl = "https://shipperdog.example.com",
            appToken = "sdc1.paste-the-fleet-token-the-console-issued",
            deviceId = "the-device-own-identifier",
            platform = "android",
            appVersion = "2.0.0",
            channel = "ota",
            verificationKeys = mapOf(
                // Base64 of the raw 32-byte Ed25519 public key, copied
                // from the console, keyed by its 16-hex keyId.
                "0123456789abcdef" to "paste-the-base64-public-key",
            ),
        )
        updater = ShipperDogOtaAndroid.start(this, ota, applier = ::applyBundle)
    }

    private fun applyBundle(manifest: OtaManifest, bundle: File): ApplyResult =
        if (manifest.forced) ApplyResult.Applied else ApplyResult.Refused
}
```

`ShipperDogOtaAndroid.start` wires the lifecycle-event queue under the
files directory, bundle staging under the cache directory, and the
poll loop — a main-thread `Handler` that runs each cycle on a
background thread and re-arms at the manifest's server-owned cadence.
Only `ShipperDogOtaAndroid.stop()` halts the loop.

## Configure and start (Swift)

On iOS the core owns no timers, no services, no threads — your app
drives the loop, re-arming at the cadence the last manifest named
(`updater.nextPollSeconds()`). The shape below, from
`sdk/ios/Samples/SDOtaDemo`, is the whole of it (re-arm on EVERY
answer — a failure never decides when you check next):

```swift
func application(_ application: UIApplication,
                 didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    let config = OtaConfig(
        baseUrl: "https://shipperdog.example.com",
        appToken: "sdc1.paste-the-fleet-token-the-console-issued",
        deviceId: UIDevice.current.identifierForVendor?.uuidString ?? "the-device-own-identifier",
        platform: "ios",
        appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "2.0.0",
        channel: "ota",
        verificationKeys: ["0123456789abcdef": "paste-the-base64-public-key"],
        queueDirectory: applicationSupportDir,   // offline lifecycle queue
        downloadDirectory: cachesDir)            // bundle staging
    let updater = OtaUpdater.start(config: config)
    startPollLoop(updater, applier: BundleSwapper())
    return true
}

private func startPollLoop(_ updater: OtaUpdater, applier: BundleApplier) {
    let queue = DispatchQueue(label: "shipperdog.ota.poll")
    let timer = DispatchSource.makeTimerSource(queue: queue)
    timer.schedule(deadline: .now())
    timer.setEventHandler {
        updater.updateNow(applier: applier) // answers a standing, never throws
        timer.schedule(deadline: .now() + .seconds(Int(updater.nextPollSeconds())))
    }
    timer.resume()
}

final class BundleSwapper: BundleApplier {
    func apply(_ manifest: OtaManifest, bundle: URL) throws -> ApplyResult {
        .applied // your bundle-swap act: hot-load, or stage for next launch
    }
}
```

## The config that matters

- **`deviceId`** — your device's own stable identifier (at most 128
  characters) and the rollout bucketing's input: the same device must
  send the same id every launch, or it re-buckets itself into
  different rollout stages.
- **`appToken` / `platform` / `appVersion` / `channel`** — the check's
  identity: the console-issued `sdc1.` token (its only credential),
  the platform tag, the app version, and the ring-vocabulary channel
  (for example `ota`).
- **`pollIntervalSeconds`** — only a fallback (default 3600) until the
  first manifest names the server's own cadence.
- **`verificationKeys`** — the embedded `keyId`-to-key map; values are
  Base64 of the raw 32-byte Ed25519 public key.

## Your half of the contract: the apply callback

The SDK hands your callback the VERIFIED bundle; what "apply" means —
hot-load it, or stage it for the next launch — is yours alone, because
only your app knows its bundle format:

- Return **applied** and the update reports `OTA_APPLIED SUCCEEDED`.
- Return **refused** — a considered refusal (low battery, a consent
  gate) — and it reports `OTA_APPLIED FAILED`.
- **Throw** — an attempted apply that broke — and it reports
  `OTA_FAILED` with reason `apply_error`.

If your app later detects a bad bundle (a crash loop) and reverts,
report the verdict: `updater.reportRolledBack()` (Kotlin) or
`updater.reportRolledBack(detail:)` (Swift).

## What the SDK does for you

- **The check** — `GET /api/v1/ota/check` with the `sdc1.` token. A
  204 means no update stands (up to date, not eligible, rollout paused
  or halted — indistinguishable by design); a 200 carries the manifest.
- **Resumable download** — stream-to-file staging with HTTP Range
  resume; a partial download continues where it stopped.
- **Verification, twice** — the staged bytes' sha256 must equal the
  manifest's label, and that label's Ed25519 signature must verify
  under the embedded key the manifest's `keyId` selects (the keyId is
  re-derived from the key as a pin). Tampered bytes report
  `OTA_FAILED checksum_mismatch`; a bad signature reports `OTA_FAILED
  signature_invalid`. The signed thing is the sha256 string — the
  signature never rides the bundle bytes.
- **Lifecycle reporting** — seen, download, apply, failure, and
  rollback events ride the short-lived `sdt1.` grant the manifest
  minted, through a bounded offline queue (at-least-once, deduplicated
  server-side by event id) that flushes on every cycle.

## Where to go deeper

- [OTA updates](/concepts/ota-updates.md) — the model this SDK serves
- [Manage OTA bundles](/how-to/console/ota-bundles.md) and
  [run OTA rollouts](/how-to/console/ota-rollouts.md) — the console side
- Developer detail: the
  [OTA device contract](https://github.com/ShristiLabs/shipperdog/blob/main/docs/ota.md)
  (wire and crypto reference), the
  [SDK reference](https://github.com/ShristiLabs/shipperdog/blob/main/docs/telemetry-sdk.md),
  and the
  [error codes](https://github.com/ShristiLabs/shipperdog/blob/main/docs/error-codes.md)
  (user-facing subset [here](/reference/error-codes.md)).

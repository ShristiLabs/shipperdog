# ShipperDog Companion (S15-02 spike prototype)

The Android companion installer app prototype of issue #73 — the
PRD §38 conditional item, evaluated spike-first. This is the
reference implementation the decision memo
([docs/companion-spike.md](../docs/companion-spike.md)) is written
against: catalog of authorized builds, one-tap PackageInstaller
install/update, installed-version tracking, feedback deep link — a
working prototype, deliberately not production polish.

## Modules

- `core/` — pure Kotlin/JVM, zero runtime dependencies (the
  `sdk/android` doctrine): portal session, catalog, eligibility,
  download issuance, install ledger, update-state logic. Fully
  covered by the JVM test suite; nothing Android-specific inside.
- `app/` — the Android surface: two activities (login, catalog), the
  PackageInstaller session flow, the unknown-apps consent routing.
  Plain framework widgets; no androidx, no compose; minSdk 26.
- `demo/` — the dogfood install target: a real AGP-built, debug-signed
  APK (`com.shipperdog.demo.banking`, the seeded demo app's registered
  package) that Android's on-device package parser accepts — unlike
  the portal e2e's minted fixture (the memo's fixture-gap finding).

## Build

The Android SDK must be locatable (auto-detected at
`~/Library/Android/sdk` on macOS; set `ANDROID_HOME` elsewhere):

```sh
cd companion
./gradlew --no-daemon :core:test :app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Dogfood

1. Run the local stack (`docker compose up` from the repo root) and
   provision a tester with an authorized Android release.
2. Install the APK on a device or emulator (`adb install -r
   app/build/outputs/apk/debug/app-debug.apk`; an emulator reaches the
   host stack at the default base `http://10.0.2.2:8080`).
3. Sign in with the tester's portal credentials, tap Install on an
   authorized build, and grant the one-time "install unknown apps"
   consent when the app routes you there.

The catalog answers live from the S7 decision path on every resume —
never a cached list — and the install state derives from the device's
own package manager facts joined against the companion's install
ledger.

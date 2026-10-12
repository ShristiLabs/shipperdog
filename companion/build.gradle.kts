// The Android companion installer app's build (issue #73, S15-02,
// PRD 38's conditional V3 item). Two modules, the sdk/android
// doctrine again: the CORE (`:core`) is a pure Kotlin/JVM library
// with ZERO runtime dependencies — login, catalog, download issuance,
// the install ledger, and the update-state logic all live there so
// the JVM test suite pins them without a device — and the APP
// (`:app`) is the thin Android surface: two activities, a
// PackageInstaller session flow, and the framework glue, plain
// framework widgets (no androidx, no compose — the spike's cost bar;
// productionization may revisit, see docs/companion-spike.md).
plugins {
    id("com.android.application") version "8.13.0" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
}

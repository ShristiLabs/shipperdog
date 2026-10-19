// The tester telemetry SDK's Android/Kotlin build (issue #72, S15-01,
// PRD 37) and its OTA updater sibling (issue #174, S16-12). Five
// modules, one doctrine: the CORE (`:telemetry`) and the OTA core
// (`:ota`) are pure Kotlin/JVM libraries with ZERO runtime
// dependencies — every platform API they touch (HTTP via
// HttpURLConnection, crash chaining via Thread's uncaught handler,
// Ed25519/SHA-256 via java.security, persistence via Files) is JDK
// API Android ships — which is how the < 200 KB budget holds by
// construction. The Android adapters (`:telemetry-android`,
// `:ota-android`) compile against the Android framework jar
// (compileOnly: the Robolectric android-all artifact on Maven
// Central — a build-time stub, never a runtime dependency); the
// sample proves the <= 5-line integration of both.
plugins {
    kotlin("jvm") version "2.2.20" apply false
}

// The tester telemetry SDK's Android/Kotlin build (issue #72, S15-01,
// PRD 37). Three modules, one doctrine: the CORE (`:telemetry`) is a
// pure Kotlin/JVM library with ZERO runtime dependencies — every
// platform API it touches (HTTP via HttpURLConnection, crash chaining
// via Thread's uncaught handler, persistence via Files) is JDK API
// Android ships — which is how the < 200 KB budget holds by
// construction. The Android adapter (`:telemetry-android`) compiles
// against the Android framework jar (compileOnly: the Robolectric
// android-all artifact on Maven Central — a build-time stub, never a
// runtime dependency) for the Application-lifecycle session tracking
// alone; the sample proves the <= 5-line integration.
plugins {
    kotlin("jvm") version "2.2.20" apply false
}

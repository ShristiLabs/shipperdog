plugins {
    kotlin("jvm")
}

// The OTA updater core (issue #174, S16-12, ADR-0010 workstream 8):
// the telemetry doctrine applied to the update funnel — pure
// Kotlin/JVM, ZERO runtime dependencies, so the < 200 KB budget the
// sdk-ci size step asserts holds by construction. Every platform API
// it touches (HTTP via HttpURLConnection, Ed25519 and SHA-256 via
// java.security, resumable persistence via Files) is JDK API Android
// ships. The Android adapter (`:ota-android`) holds the only
// framework-touching code (directory wiring).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

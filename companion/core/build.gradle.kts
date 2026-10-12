plugins {
    kotlin("jvm")
}

// The companion's core: pure Kotlin/JVM, ZERO runtime dependencies —
// the sdk/android telemetry core's doctrine (HttpURLConnection,
// injectable seams, hand-rolled JSON) applied to the companion's own
// problem: tester-session auth, the authorized catalog, download
// issuance, and the install ledger that makes update detection
// possible. Everything here runs in the JVM test suite without an
// emulator; nothing Android-specific may leak in.
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

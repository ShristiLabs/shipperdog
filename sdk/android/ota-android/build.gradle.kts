plugins {
    kotlin("jvm")
}

// The OTA updater's Android adapter: the app-directory wiring alone
// (the queue and bundle staging under the app's own storage), the
// telemetry adapter's doctrine — the framework jar is compileOnly
// (the Robolectric android-all stub from Maven Central, a build-time
// stand-in never packaged, never a runtime dependency; the app itself
// supplies the real framework classes).
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
    api(project(":ota"))
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
}

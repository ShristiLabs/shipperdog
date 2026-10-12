plugins {
    kotlin("jvm")
}

// The Android adapter: the Application-lifecycle session tracking and
// the feedback deep-link intent — the ONLY Android-API-touching code
// of the SDK. The framework jar is compileOnly (the Robolectric
// android-all artifact: the real AOSP framework classes, a
// build-time stub from Maven Central, never packaged, never a
// runtime dependency — the app itself supplies the framework).
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
    api(project(":telemetry"))
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
}

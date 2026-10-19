plugins {
    kotlin("jvm")
}

// The docs-proven sample (AC1): the COMPLETE integration of the SDK
// in an Android application — two statements in Application.onCreate
// and nothing else. Compiled in CI against the same framework stub
// as the adapter so the <= 5-line promise is machine-checked, not
// prose-checked.
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
    implementation(project(":telemetry-android"))
    implementation(project(":ota-android"))
    compileOnly("org.robolectric:android-all:15-robolectric-12650502")
}

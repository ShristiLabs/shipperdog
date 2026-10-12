plugins {
    id("com.android.application")
    kotlin("android")
}

// The demo build (issue #73, S15-02): a REAL APK from the Android
// toolchain — the install target the spike's E2E dogfood needs. The
// portal e2e's APK minter satisfies the backend's structural
// validation but not Android's on-device package parser (the spike's
// own E2E run proved the difference), so the companion's dogfood
// target is a genuine AGP build with the demo application's real
// identity: package com.shipperdog.demo.banking — the package the
// seeded demo app registers. One activity, one label, nothing else.
android {
    namespace = "com.shipperdog.demo.banking"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shipperdog.demo.banking"
        minSdk = 26
        targetSdk = 35
        versionCode = 300
        versionName = "9.99999.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

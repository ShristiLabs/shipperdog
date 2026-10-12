plugins {
    id("com.android.application")
    kotlin("android")
}

// The companion app itself (issue #73, S15-02): the thin Android
// surface over the pure-JVM core — two activities (login, catalog),
// a PackageInstaller session flow, and the unknown-apps permission
// routing. Plain framework widgets only (no androidx, no compose):
// the spike's cost bar — the memo (docs/companion-spike.md) records
// what productionization would revisit. minSdk 26 is the honest
// floor: REQUEST_INSTALL_PACKAGES and the per-app "install unknown
// apps" consent are the Android 8.0 design.
android {
    namespace = "com.shipperdog.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shipperdog.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-spike"
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

dependencies {
    implementation(project(":core"))
}

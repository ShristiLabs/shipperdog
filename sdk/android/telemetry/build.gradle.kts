plugins {
    kotlin("jvm")
}

// The core beacon: pure Kotlin/JVM, ZERO runtime dependencies (the
// < 200 KB budget holds by construction — the size check in CI
// asserts the built jar). jvmTarget 17 keeps the artifact consumable
// by modern Android (AGP 8's default bytecode level) while compiling
// on any newer JDK.
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

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "shipperdog-telemetry-sdk"

include(":telemetry")
include(":telemetry-android")
include(":ota")
include(":ota-android")
include(":sample")

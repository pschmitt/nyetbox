rootProject.name = "nyetbox"

include(":app")
include(":baselineprofile")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
plugins {
    // Lets Gradle download the JDK 17 toolchain the build asks for when the machine only has a newer JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
dependencyResolutionManagement {
    repositories {
        if (providers.gradleProperty("joyframe.usePublished").orNull == "true") maven { url=uri("build/staging") }
        google(); mavenCentral()
    }
}
rootProject.name = "joyframe-kmp"
include(":joyframe", ":sample", ":sample-android", ":phonepad")

plugins {
    // Downloads the JDKs the tests run on (21 and 25) when they are not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "minimp3-java"

// settings.gradle.kts
// Root Gradle settings: project name and module inclusion.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // Gradle 9 auto-discovers gradle/libs.versions.toml as the "libs" catalog;
    // the explicit versionCatalogs block is omitted to avoid the deprecation warning.
}

rootProject.name = "TermEmuProject"
include(":app")

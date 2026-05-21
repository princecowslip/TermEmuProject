// build.gradle.kts (root)
// Top-level build file: registers plugins without applying them to the root project.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// No further configuration at root scope.
// All module-specific configurations are handled inside app/build.gradle.kts.

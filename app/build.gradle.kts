// app/build.gradle.kts
// Module-level build configuration for the TermEmuProject Android app.

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.yourname.termemu"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.1"

    defaultConfig {
        applicationId = "com.yourname.termemu"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Prioritise arm64-v8a; include x86_64 for emulator convenience
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        // Bind CMake 4.1.2 build system
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_CPP_FEATURES=rtti exceptions"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.oboe)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// microWakeWord detector, vendored from the Home Assistant Android app (Apache License 2.0).
// See LICENSE-home-assistant-android.md. Native code is built with CMake and downloads
// tflite-micro and friends from GitHub at build time.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.homeassistant.companion.android.microwakeword"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

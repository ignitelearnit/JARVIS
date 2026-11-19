plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.virr.jarvis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.virr.jarvis"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // "Hey Jarvis" wake word detector (open source, runs on the phone)
    implementation(project(":microwakeword"))
}

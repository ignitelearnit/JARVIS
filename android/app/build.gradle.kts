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
        versionName = "0.1"
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
    // Wake word engine. Free personal AccessKey from console.picovoice.ai
    implementation("ai.picovoice:porcupine-android:3.+")
}

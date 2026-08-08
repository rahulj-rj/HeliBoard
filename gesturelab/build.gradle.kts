import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gesture Lab — minimal standalone dev app for the :gesture decoder.
// Installs side-by-side with the keyboard (own applicationId). Draw a swipe on the
// rendered qwerty, see inflection points and per-scorer candidates, and optionally
// record swipes to a JSONL corpus for tuning.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "helium314.keyboard.gesturelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "helium314.keyboard.gesturelab"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        target {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }
}

dependencies {
    implementation(project(":gesture"))
}

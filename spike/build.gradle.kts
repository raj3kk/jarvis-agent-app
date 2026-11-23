import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.spike.mi1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.spike.mi1"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "mi1-spike-1"

        // The user's phone is arm64 — same call as the app (1.4.12).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Debug-signed only. This spike is NEVER a release: no
            // production keystore, no release slot, no version bump.
            isMinifyEnabled = false
        }
        release {
            // Never built by the spike workflow; keep it harmless.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    // M-I-1: the whole point of the spike is that the .so is NOT in
    // the APK — it is downloaded on demand (the public Google Maven
    // AAR) and System.load-ed from filesDir. Exclude it from
    // packaging: the Java API classes from the AAR still ship, and
    // they resolve their native methods lazily at first call, after
    // our System.load. The CI workflow FAILS the build if the APK
    // still contains liblitertlm_jni.so (negative gate).
    packaging {
        jniLibs {
            excludes += "lib/arm64-v8a/liblitertlm_jni.so"
            excludes += "lib/x86_64/liblitertlm_jni.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // LiteRT-LM 0.18.0 Java API only (the .so is excluded above).
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

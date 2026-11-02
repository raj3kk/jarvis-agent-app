import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production signing: if keystore.properties exists at the repo root
// (created in CI from GitHub Secrets, never committed — see .gitignore),
// use it. Otherwise fall back to the debug key so the APK stays
// installable, and the CI verify step labels it debug-signed.
// A debug-signed release must NOT be presented as production-signed:
// changing the key later forces users to uninstall/reinstall.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasReleaseKeystore = keystorePropertiesFile.exists().also {
    if (it) keystorePropertiesFile.inputStream().use { stream -> keystoreProperties.load(stream) }
}

android {
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    namespace = "com.jarvis.autonomous"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.autonomous"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.1.1-PRO"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Production key if keystore.properties was supplied, else debug
            // key so the APK is directly installable (labelled as such by CI).
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin 2.4: kotlinOptions.jvmTarget is an error — compilerOptions DSL only.
    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // HF token for the gated Gemma download, injected at build time
    // from the HF_TOKEN Actions secret. Empty on local builds without
    // the secret — then the user-entered token in AI Brain is used.
    // This value is in the built APK binary, never in repo source.
    buildConfigField(
        "String", "HF_TOKEN",
        "\"" + (System.getenv("HF_TOKEN") ?: "") + "\""
    )

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines & Networking
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Free on-device LLM (LiteRT-LM 0.18.0, Google Maven). No account/card.
    // Localhost preview uses plain java.net ServerSocket (no dependency):
    // org.nanohttpd:nanohttpd:2.3.2 does not exist on Maven Central and
    // broke CI dependency resolution on 2026-11-02, so it is NOT used.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}

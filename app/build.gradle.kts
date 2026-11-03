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
                // keystore.properties lives at the REPO ROOT (CI writes it there),
                // so resolve storeFile against rootProject — plain file() would
                // look inside app/ and validateSigningRelease would fail.
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
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
        versionCode = 17
        versionName = "1.4.9-PRO"

        // Owner's HF token for the gated Gemma download (user order
        // 2026-11-02 21:28 IST: "User kyun dega HF token? Mera wala
        // token use karo"). CI passes the GitHub Actions secret
        // HF_TOKEN as an env var; when absent the field is "" and
        // the app falls back to asking for a token in AI Brain.
        // HONEST: this token IS embedded in the APK and CAN be
        // extracted by a determined user — it is the owner's
        // read-only Gemma-download token, revocable/rotatable from
        // Hugging Face settings. It is never claimed unextractable.
        // It is never printed to build logs (Gradle does not log
        // buildConfigField values).
        buildConfigField(
            "String", "HF_TOKEN",
            "\"" + (System.getenv("HF_TOKEN") ?: "")
                .replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        )

        vectorDrawables {
            useSupportLibrary = true
        }

        // 1.4.0 Kokoro-only: ship native libs for arm64-v8a only.
        // Both native stacks ship arm64-v8a (Sherpa-ONNX static AAR
        // and LiteRT-LM Android), and the user's phone is arm64, so
        // this does not break either lib on the target device. It
        // drops the x86_64 slice (emulator-only) and saves a few MB
        // against the ~190 MB bundled Kokoro assets. CI GATE: if
        // the 1.4.0 build fails with a missing-native-lib error,
        // revert this block first (keep both ABIs) before touching
        // anything else — see INTEGRATION_NOTES_Kokoro.md §4.
        ndk {
            abiFilters += "arm64-v8a"
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

    // Token policy change (user order 2026-11-02 21:28 IST): the
    // owner's read-only Gemma-download HF token IS embedded via
    // BuildConfig (see defaultConfig above) so the end user does
    // NOT have to enter any HF token. It can be extracted from the
    // APK by a determined user and is revocable/rotatable from HF
    // settings — never claim otherwise.
    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Deliberately NO aapt noCompress for onnx/bin: Sherpa never
        // reads APK assets directly. KokoroTtsClient copies assets
        // via AssetManager streams (which decompress on the fly) to
        // the files dir, and Sherpa loads those copies. noCompress
        // would only inflate the APK further for zero benefit.
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines & Networking
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Sherpa-ONNX (Kokoro offline voice) — AAR is downloaded in CI,
    // never committed (37 MB, would bloat the repo). The workflow
    // downloads the same file to app/libs/sherpa-onnx.aar before
    // building; local builds need that file present too.
    implementation(files("libs/sherpa-onnx.aar"))

    // Free on-device LLM (LiteRT-LM 0.18.0, Google Maven). No account/card.
    // Localhost preview uses plain java.net ServerSocket (no dependency):
    // org.nanohttpd:nanohttpd:2.3.2 does not exist on Maven Central and
    // broke CI dependency resolution on 2026-11-02, so it is NOT used.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
}

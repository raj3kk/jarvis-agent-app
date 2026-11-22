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
        versionCode = 46
        versionName = "1.10.0-PRO"

        // 2b-3 (B1 fix): NO embedded HF token any more. The gated Gemma
        // download uses ONLY the token the phone's owner types in AI
        // Brain (JarvisPreferences.hfToken) — the old BuildConfig
        // .HF_TOKEN field (CI secret HF_TOKEN) was deleted here and in
        // the workflow because the literal is extractable from DEX
        // strings. No token + no downloaded model = an honest message.

        vectorDrawables {
            useSupportLibrary = true
        }

        // 1.4.12: ship native libs for arm64-v8a only.
        // Only LiteRT-LM Android ships native code now (the
        // VAD engine AAR was removed in 1.4.23-PRO step 2c),
        // and the user's phone is arm64, so this does not
        // break it on the target device. It drops the x86_64
        // slice (emulator-only) and saves a few MB. CI GATE: if
        // the build fails with a missing-native-lib error, revert
        // this block first (keep both ABIs) before touching
        // anything else.
        ndk {
            abiFilters += "arm64-v8a"
        }

    }

    buildTypes {
        release {
            // 1.5.4-PRO: R8 on (keep-all app code — proguard-rules.pro
            // UNCHANGED, safe baseline; shrink sirf dependencies ka).
            isMinifyEnabled = true
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

    // 1.4.21 Agent Mode: encrypted storage for write-capable
    // provider tokens (GitHub PAT / Supabase PAT / Vercel token).
    // EncryptedSharedPreferences (MasterKey AES256_GCM) — the ONLY
    // new dependency in this build. If it ever fails to resolve,
    // the build FAILS loudly; there is deliberately NO fallback
    // to plain SharedPreferences for these tokens.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Free on-device LLM (LiteRT-LM 0.18.0, Google Maven). No account/card.
    // Localhost preview uses plain java.net ServerSocket (no dependency):
    // org.nanohttpd:nanohttpd:2.3.2 does not exist on Maven Central and
    // broke CI dependency resolution on 2026-11-02, so it is NOT used.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // 1.7.1 (I1): unit tests for on-device document memory
    // (DocumentMemoryTest). JUnit4 for the pure search/chunk logic;
    // Robolectric so the file-backed store can be tested with a real
    // Context (filesDir) on the JVM — no emulator, no device.
    // 1.8.0 (I2): same JUnit4 covers the Talker/Executor protocol,
    // bus, dispatcher and reply policy (pure-Kotlin modules).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}

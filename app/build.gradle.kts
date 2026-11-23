import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 1.12.0 FCM: applies app/google-services.json (Firebase project
    // omnijarvis-2bdd6, package com.jarvis.autonomous).
    id("com.google.gms.google-services")
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
    // 1.15.0 Gecko REMOVED (redesign R0): the checkAarMetadata
    // suppression left with the 160.x AARs. compileSdk stays 36
    // (no AC dep needs it any more; lowering it is a separate
    // decision, not churned into this release). The AGP 9.1 +
    // compileSdk 37 migration stays a deferred follow-up.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jarvis.autonomous"
        minSdk = 26
        targetSdk = 34
        versionCode = 54
        versionName = "1.18.0-PRO"

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

    // 1.17.0 (R2 Trained Jarvis): ML Kit on-device text recognition
    // for photo ingestion (Latin + Devanagari). BUNDLED 16.0.1 both
    // scripts — the model ships inside the APK, so OCR works offline
    // at first use with no Play Services model download (the
    // unbundled Play-Services form is 19.0.2/19.0.1 and downloads on
    // first use, which can fail offline). Cost ~4 MB per script per
    // arch (~8 MB total on arm64-v8a); APK ~25 MB -> ~33 MB, well
    // under the 100 MB cap. Apache-2.0 (Google ML Kit, proprietary
    // models but free, no card). Task.await() comes from the
    // kotlinx-coroutines-play-services dep already in the tree.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

    // 1.15.0 (R0 Whop browser): androidx.webkit for
    // WebSettingsCompat.setUserAgentMetadata — desktop Client Hints
    // (Sec-CH-UA-Mobile: ?0) need it; a UA string alone is not
    // enough (the server keeps serving the mobile bundle).
    implementation("androidx.webkit:webkit:1.12.1")

    // 1.15.0 (R0 Whop browser): DSBridge (MIT, wendux/DSBridge-Android
    // via JitPack) — the JobEngine's 1-method JS bridge (DWebView +
    // addJavascriptObject). Pinned to the master-HEAD commit
    // 22b19ffaf1 ("fix #85") — NOT the README's 3.0-SNAPSHOT: CI
    // 45132051346 PROVED the SNAPSHOT unresolvable (JitPack builds API
    // says master=Error; the 3.0.0 tag has a pom but NO aar). The
    // commit-pinned coordinate is verified live: JitPack builds API
    // "22b19ffaf1":"ok", pom 200 (1,397 B) + aar 200 (37,056 B).
    // The page-side half (dsbridge.js v3.1.3, MIT) is vendored in
    // app/src/main/assets/ and injected per page by the engine — no
    // CDN load at runtime. If CI ever fails to resolve this pinned
    // commit, the fallback is vendoring the AAR source — never a
    // second bridge implementation.
    // TRANSITIVE-DEPS FIX (CI 45135413509 + 45136651748): DSBridge's
    // pom depends on appcompat-v7:23.4.0, which pulls
    // support-v4:23.4.0 — whose android.support.v4.os.ResultReceiver
    // / INotificationSideChannel classes ALSO ship in
    // androidx.core:1.13.1, so :app:checkDebugDuplicateClasses fails.
    // Jetifier alone does NOT fix it (commit #6 PROVED it): those
    // AIDL classes are deliberately excluded from Jetifier rewriting
    // (their package is part of the Binder wire protocol), so the
    // duplicates survive in the original package. The fix is
    // exclusion: support-v4 + appcompat-v7 are BOTH excluded from
    // DSBridge (its classes reference ONLY android.support.v7.app
    // .AlertDialog + android.support.annotation.Keep — verified by
    // reading its classes.jar constant pool), and androidx.appcompat
    // is added directly below. Jetifier (gradle.properties) STAYS ON:
    // it rewrites DSBridge's AlertDialog/Keep references to the
    // androidx equivalents. A blanket exclude of com.android.support
    // would be wrong for the same reason as before — DWebView calls
    // AlertDialog.Builder at runtime.
    implementation("com.github.wendux:DSBridge-Android:22b19ffaf1") {
        exclude(group = "com.android.support", module = "support-v4")
        exclude(group = "com.android.support", module = "appcompat-v7")
    }
    // 1.15.0 (R0): AndroidX replacement for DSBridge's excluded
    // appcompat-v7 (its AlertDialog, rewritten by Jetifier, needs it).
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 1.15.0 (R0 parity spec §5 #10): WorkManager backstop — the Vivo
    // kills background WebViews/services, so a periodic worker re-arms
    // the foreground service when a plan or browser job is pending.
    // 15-min minimum interval (WorkManager floor); the user must allow
    // the battery exemption once (already prompted, A-M8) or the OS
    // may still defer the worker — see BUILD_NOTES phone preconditions.
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Free on-device LLM (LiteRT-LM 0.18.0, Google Maven). No account/card.
    // Localhost preview uses plain java.net ServerSocket (no dependency):
    // org.nanohttpd:nanohttpd:2.3.2 does not exist on Maven Central and
    // broke CI dependency resolution on 2026-11-02, so it is NOT used.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // 1.12.0 FCM: kotlinx-coroutines-play-services provides the
    // `Task.await()` extension used by FcmTokenClient (Firebase token
    // fetch). Same version as kotlinx-coroutines-android (1.8.1).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // 1.12.0 FCM: Firebase Cloud Messaging for hub -> phone push
    // (data-only messages; the app renders the notification). Direct
    // pin, no BOM, so resolution cannot drift. google-services.json
    // (Firebase project omnijarvis-2bdd6) is client config, not a secret.
    implementation("com.google.firebase:firebase-messaging:24.0.0")

    // 1.7.1 (I1): unit tests for on-device document memory
    // (DocumentMemoryTest). JUnit4 for the pure search/chunk logic;
    // Robolectric so the file-backed store can be tested with a real
    // Context (filesDir) on the JVM — no emulator, no device.
    // 1.8.0 (I2): same JUnit4 covers the Talker/Executor protocol,
    // bus, dispatcher and reply policy (pure-Kotlin modules).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    // 1.14.0 (M-I-30): real org.json on the TEST classpath only.
    // android.jar's org.json is a stub that throws RuntimeException
    // ("Stub!") in local unit tests, so any test that builds a
    // JSONObject (CronJobClientTest) fails without this. The real jar
    // shadows the stub on the unit-test classpath; production code
    // still uses the device's org.json — nothing ships differently.
    testImplementation("org.json:json:20240303")
}

// Toolchain 2026-11-02: Kotlin 2.4.0 is REQUIRED, not optional —
// the Compose compiler + AGP 8.11.0 pairing needs it (1.9 failed
// on newer metadata in CI runs #10 and #11). AGP 8.11.0 + Gradle
// 8.13 go with it. Under Kotlin 2.x the Compose compiler is the
// kotlin.plugin.compose plugin, NOT
// composeOptions.kotlinCompilerExtensionVersion (removed below).
// (1.20.0 note: the old LiteRT-LM reason for pinning 2.4.0 is gone
// with Gemma; the pin stays for the Compose/AGP pairing above.)
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    // 1.12.0 FCM: google-services plugin reads app/google-services.json
    // (client config from the Firebase console — NOT a secret).
    id("com.google.gms.google-services") version "4.4.2" apply false
}

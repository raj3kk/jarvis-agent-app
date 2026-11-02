// Toolchain 2026-11-02: Kotlin 2.4.0 is REQUIRED, not optional —
// LiteRT-LM 0.18.0's stdlib classes carry Kotlin 2.4 metadata: 1.9
// failed on 2.2 metadata, then 2.2 failed on 2.4 metadata (CI logs,
// runs #10 and #11). AGP 8.11.0 + Gradle 8.13 go with it. Under Kotlin
// 2.x the Compose compiler is the kotlin.plugin.compose plugin, NOT
// composeOptions.kotlinCompilerExtensionVersion (removed below).
plugins {
    id("com.android.application") version "8.11.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}

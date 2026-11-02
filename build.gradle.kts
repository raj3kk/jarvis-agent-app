// Toolchain 2026-11-02: Kotlin 2.2.0 is REQUIRED, not optional —
// LiteRT-LM 0.18.0 is compiled with Kotlin 2.2/2.4 metadata and the
// 1.9.24 compiler cannot read it (CI: "compiled with an incompatible
// version of Kotlin"). AGP 8.7.3 + Gradle 8.9 go with it. Under Kotlin
// 2.x the Compose compiler is the kotlin.plugin.compose plugin, NOT
// composeOptions.kotlinCompilerExtensionVersion (removed below).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}

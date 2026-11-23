pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 1.15.0 Gecko REMOVED (redesign R0): the Mozilla Maven repo
        // left with the Android Components deps — no org.mozilla.*
        // artifact is resolved any more.
        // 1.15.0 (R0 Whop browser): JitPack hosts DSBridge-Android
        // (wendux/DSBridge-Android, MIT) — the JobEngine's 1-method
        // JS bridge. No auth. Declared here (FAIL_ON_PROJECT_REPOS
        // forbids project-level repos in app/build.gradle.kts).
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "JarvisAgentApp"
include(":app")

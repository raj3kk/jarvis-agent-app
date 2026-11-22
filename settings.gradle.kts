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
        // GeckoView spike (Phase 0, branch geckoview-spike only):
        // Mozilla Maven repo — REQUIRED for GeckoView + Android
        // Components. No auth. Must live here (FAIL_ON_PROJECT_REPOS
        // forbids project-level repos in app/build.gradle.kts).
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "JarvisAgentApp"
include(":app")

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
        // 1.13.0 Gecko browser: Mozilla Maven repo — REQUIRED for
        // GeckoView + Android Components. No auth. Must live here
        // (FAIL_ON_PROJECT_REPOS forbids project-level repos in
        // app/build.gradle.kts).
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "JarvisAgentApp"
include(":app")

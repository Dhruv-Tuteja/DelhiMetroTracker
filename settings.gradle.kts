// settings.gradle.kts
pluginManagement {
    plugins {
        // Use the simplified versioning for KSP in 2026
        id("com.google.devtools.ksp") version "2.3.4"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack is still useful for specific metro-related map or utility libraries
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "DelhiMetroTracker"
include(":app")
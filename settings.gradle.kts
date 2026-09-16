@file:Suppress("UnstableApiUsage")

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
        maven("https://jitpack.io")
    }
}

// Kagura started out as a fork of Dantotsu, so this is just giving the
// Gradle project its own name instead of still calling itself Dantotsu
// in build logs, Android Studio's window title, etc.
rootProject.name = "Kagura"
include(":app")

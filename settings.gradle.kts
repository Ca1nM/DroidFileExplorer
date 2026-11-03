pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 7-Zip JBinding for Android (reads RAR and RAR5, including split archives).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.omicronapps") }
        }
    }
}

rootProject.name = "DroidFileExplorer"
include(":app")

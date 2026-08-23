pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // Modules declare no repositories of their own, so there is exactly one place that
    // decides where a dependency may come from.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

// Root Gradle configuration for Civic 5MT Telemetry Android Application
rootProject.name = "civic5mt"

include(":core")
include(":app")

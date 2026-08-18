pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
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

// The native app's build. Deliberately a separate Gradle build from `android/`, which is
// Capacitor's generated project - that one is still what ships to the car and is not
// something to entangle with this.
rootProject.name = "civic5mt-native"

include(":core")

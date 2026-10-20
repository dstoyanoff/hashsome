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
    }
}
rootProject.name = "hashsome"

// Dependency direction, enforced by each module's own build file: kiosk -> ui -> headless -> client; testing -> client.
include(":client", ":headless", ":ui", ":kiosk", ":testing")

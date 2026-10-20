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
rootProject.name = "hashsome-example"

// The libraries come from this repo's own build: Gradle substitutes every dev.hashsome:<module> dependency with the
// included project of that group and name, so the example always builds against the source next to it. A consumer
// outside this repo would declare the GitHub Packages repository instead.
includeBuild("../../android")

include(":app")

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    testOptions {
        // `-Phashsome.smoke=host:port` arms RuntimeSmokeTest; without it that test is skipped.
        unitTests.all { it.systemProperty("hashsome.smoke", providers.gradleProperty("hashsome.smoke").getOrElse("")) }
    }
}

dependencies {
    testImplementation(libs.junit)
    // The platform's own org.json is a stub on the JVM, so unit tests bring the real one.
    testImplementation(libs.org.json)
    testImplementation(project(":testing"))
}

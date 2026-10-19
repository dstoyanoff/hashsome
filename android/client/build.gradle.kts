plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    testImplementation(libs.junit)
    // The platform's own org.json is a stub on the JVM, so unit tests bring the real one.
    testImplementation(libs.org.json)
    testImplementation(project(":testing"))
}

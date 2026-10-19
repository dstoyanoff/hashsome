plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    api(project(":headless"))
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

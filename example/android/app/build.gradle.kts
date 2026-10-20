import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// The libraries' version is the one in the included build, so the example's versionName/Code follow the release.
val hashsomeVersion: String = Properties()
    .apply { file("../../../android/gradle.properties").inputStream().use { load(it) } }
    .getProperty("hashsome.version")

/** major*10000 + minor*100 + patch, as in android/build.gradle.kts: monotonic across releases. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.substringBefore('-').split('.').map { it.toInt() }
    return major * 10000 + minor * 100 + patch
}

android {
    namespace = "dev.hashsome.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.hashsome.example"
        minSdk = 24
        targetSdk = 34
        versionCode = versionCodeOf(hashsomeVersion)
        versionName = hashsomeVersion
    }

    buildTypes {
        release {
            // Signed with the debug key: the example is built in CI and sideloaded, never published.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The example is a plain dashboard app: no kiosk behaviour (`withKiosk` is not installed), so `ui` alone, plus `kiosk` only to open
    // its settings sheet for a look (`--es settings true`, see DemoLaunch).
    implementation(libs.hashsome.ui)
    implementation(libs.hashsome.kiosk)
}

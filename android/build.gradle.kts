import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

val hashsomeVersion: String = providers.gradleProperty("hashsome.version").get()

/** The monotonic Android versionCode of a `major.minor.patch` version, e.g. 0.19.2 -> 1902. Apps use it, never the libraries. */
fun versionCodeOf(version: String): Int {
    val (major, minor, patch) = version.substringBefore('-').split('.').map { it.toInt() }
    return major * 10000 + minor * 100 + patch
}
extra["versionCodeOf"] = ::versionCodeOf
extra["hashsomeVersion"] = hashsomeVersion

subprojects {
    group = "dev.hashsome"
    version = hashsomeVersion

    pluginManager.withPlugin("com.android.library") {
        apply(plugin = "maven-publish")

        extensions.configure<LibraryExtension> {
            namespace = "dev.hashsome.${project.name}"
            compileSdk = 34
            defaultConfig { minSdk = 24 }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // Lint is part of `build`: an API above minSdk 24 must fail the build, not a device in the field.
            lint {
                abortOnError = true
                fatal += "NewApi"
            }
            testOptions.unitTests.isReturnDefaultValues = true
            publishing { singleVariant("release") { withSourcesJar() } }
        }

        // Applied after the Android plugin in each module's `plugins {}`, so it is configured when the Kotlin plugin arrives.
        pluginManager.withPlugin("org.jetbrains.kotlin.android") {
            extensions.configure<KotlinAndroidProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("release") {
                        from(components["release"])
                        groupId = "dev.hashsome"
                        artifactId = project.name
                        version = hashsomeVersion
                    }
                }
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/dstoyanoff/hashsome")
                        credentials {
                            username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                            password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
                        }
                    }
                }
            }
        }
    }
}

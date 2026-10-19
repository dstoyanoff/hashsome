plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

dependencies {
    api(project(":client"))
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(project(":testing"))
}

// The headless layer is what a view binds to: it must run on a plain JVM, so no source here may name android.* or
// androidx.* at all (an import, a fully qualified use, or a mention that is about to become one).
val checkNoAndroidImports by tasks.registering {
    val sources = fileTree("src/main") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val pattern = Regex("""\bandroidx?\.""")
        val offenders = sources.files.filter { f -> f.readLines().any { pattern.containsMatchIn(it) } }
        check(offenders.isEmpty()) { "headless must not use android.* or androidx.*: ${offenders.map { it.name }}" }
    }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoAndroidImports) }

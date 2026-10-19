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

// The headless layer is what a view binds to: it must run on a plain JVM, so no source here may import android.*.
val checkNoAndroidImports by tasks.registering {
    val sources = fileTree("src/main") { include("**/*.kt") }
    inputs.files(sources)
    doLast {
        val offenders = sources.files.filter { f -> f.readLines().any { it.trimStart().startsWith("import android.") } }
        check(offenders.isEmpty()) { "headless must not import android.*: ${offenders.map { it.name }}" }
    }
}
tasks.matching { it.name == "check" }.configureEach { dependsOn(checkNoAndroidImports) }

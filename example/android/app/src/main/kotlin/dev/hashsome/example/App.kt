package dev.hashsome.example

import android.app.Application
import dev.hashsome.headless.ThemeSchedule
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.HashsomeApp

/** The example's whole setup: one place that says which dashboards exist and how the theme is chosen. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hashsome.install(
            this,
            HashsomeApp(
                theme = ThemeSchedule.Fixed(dark = true),
                dashboards = listOf(Demo),
            ),
        )
    }
}

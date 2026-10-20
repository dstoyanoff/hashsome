package dev.hashsome.example

import android.os.Bundle
import dev.hashsome.ui.DashboardActivity
import dev.hashsome.ui.Hashsome

/**
 * The dashboard activity of the example: it takes the runtime's address from the launch intent (`--es server 10.1.1.5:3000`) and
 * remembers it, so starting the app from the launcher goes to the same runtime. A real project provisions this through `kiosk`.
 */
class DemoActivity : DashboardActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("demo", MODE_PRIVATE)
        intent.getStringExtra("server")?.let { prefs.edit().putString("server", it).apply() }
        prefs.getString("server", null)?.let { Hashsome.settings.server.set(if ("://" in it) it else "http://$it") }
        super.onCreate(savedInstanceState)
    }
}

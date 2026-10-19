package dev.hashsome.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.hashsome.ui.Hashsome

/**
 * Takes `--es server host:port` and `--es dashboard <id>` (and `--ez perf true` for the frame-gap beacon) from the intent that starts the app, and keeps them for the next start, as a kiosk
 * is provisioned: the example is a plain app with no kiosk module, so this stands in for it.
 */
object DemoLaunch {
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(app: Application) {
        val prefs = app.getSharedPreferences("demo", Context.MODE_PRIVATE)
        // `am broadcast -a dev.hashsome.PROVISION --es dashboard <id>`, as a kiosk is provisioned: switches the running app, in place.
        val provision = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                intent.getStringExtra("dashboard")?.let {
                    prefs.edit().putString("dashboard", it).apply()
                    Hashsome.settings.dashboard.set(it)
                }
            }
        }
        val filter = android.content.IntentFilter("dev.hashsome.PROVISION")
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(provision, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(provision, filter)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val intent = activity.intent
                intent?.getStringExtra("server")?.let { prefs.edit().putString("server", it).apply() }
                intent?.getStringExtra("dashboard")?.let { prefs.edit().putString("dashboard", it).apply() }
                if (intent?.hasExtra("perf") == true) prefs.edit().putBoolean("perf", intent.getBooleanExtra("perf", false)).apply()
                prefs.getString("server", null)?.let { Hashsome.settings.server.set(it) }
                prefs.getString("dashboard", null)?.let { Hashsome.settings.dashboard.set(it) }
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

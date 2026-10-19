package dev.hashsome.kiosk

// The keep-in-front check adapts ShellyElevate's ForegroundDetector (Apache-2.0, see android/NOTICE): usage-stats events,
// read incrementally, with a fallback when the appop was not granted.

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler
import dev.hashsome.ui.Hashsome
import org.json.JSONObject

/** Foreground service: keeps the dashboard in front (checks every 2 s through UsageStatsManager and relaunches it) and owns presence, night mode, auto brightness and the MQTT bridge. */
class KioskService : Service() {
    private var rebuilder: Rebuilder? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Within five seconds of startForegroundService, whatever else happens.
        startInForeground()
        if (rebuilder == null) {
            // The displays are mains-powered: the CPU stays up while the panel is dark, so the idle timer, the MQTT keep-alive and the
            // proximity sensor's events are never starved (spike S2b left proximity-while-dark open; this removes the doubt about timers).
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hashsome:service").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                rebuilder = Rebuilder(Settings) { build() }
            } catch (e: Exception) {
                Log.e("service", "could not start the kiosk", e)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        rebuilder?.close()
        rebuilder = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Kiosk", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val icon = applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        startForeground(NOTIFICATION_ID, builder.setSmallIcon(icon).setContentTitle("Hashsome").setContentText("Keeping the dashboard in front").setOngoing(true).build())
    }

    /** Creates the parts that depend on the profile, and returns how to tear them down. */
    private fun build(): () -> Unit {
        val closers = ArrayList<() -> Unit>()
        val sched = mainScheduler()
        val profile = DeviceProfile.detect()
        Log.i("service", "profile ${profile.id} (device ${Build.DEVICE}, model ${Build.MODEL}), device id ${Settings.deviceId.value}")

        val screen = profile.screen(this)
        closers += { (screen as? java.io.Closeable)?.close() }
        val presence = profile.presence(this, sched)
        presence.start()
        closers += { presence.stop() }
        val night = NightMode(Settings, presence, screen, sched)
        closers += { night.close() }
        val brightness = BrightnessControl(Settings, presence, screen)
        brightness.scheduler = sched
        brightness.start()
        closers += { brightness.stop() }

        Kiosk.presence = presence
        Kiosk.screen = screen
        Kiosk.nightMode = night
        closers += {
            Kiosk.presence = null
            Kiosk.screen = null
            Kiosk.nightMode = null
        }

        val state = Kiosk.state
        closers += presence.lux.listenNow { state.lux.set(it) }
        closers += presence.near.listenNow { state.occupied.set(it) }
        closers += screen.state.listenNow { state.sleeping.set(it == ScreenState.Off) }

        val bridge = MqttBridge(Settings, state, Kiosk.actions, PahoMqttLink(sched), Hashsome.app.dashboards.map { it.id })
        bridge.scheduler = sched
        bridge.appVersion = appVersion(this)
        bridge.profileId = profile.id
        bridge.start()
        closers += { bridge.stop() }

        val linkBinder = LinkBinder(state)
        val front = KeepInFront(this)
        // Also while the screen is off: a stock popup must not sit in front of the dashboard when it wakes.
        closers += sched.every(CHECK_MS) {
            linkBinder.bindIfPossible()
            front.check()
        }
        return { closers.reversed().forEach { it() } }
    }

    private companion object {
        const val CHANNEL = "hashsome.kiosk"
        const val NOTIFICATION_ID = 1
        const val CHECK_MS = 2_000L
    }
}

internal fun appVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (e: Exception) {
    "unknown"
}

/**
 * Builds the parts that depend on the device profile ([build] returns how to tear them down) and builds them again, at once, when
 * `Settings.profileOverride` changes, so a new profile from the sheet or a provisioning broadcast does not wait for a restart.
 * (A new `deviceId` needs no rebuild: the bridge moves itself, retracting the old discovery.)
 */
internal class Rebuilder(settings: Settings, private val build: () -> () -> Unit) {
    private var teardown: () -> Unit = build()
    private val listen = settings.profileOverride.listen { rebuild() }

    fun rebuild() {
        teardown()
        teardown = build()
    }

    fun close() {
        listen()
        teardown()
        teardown = {}
    }
}

/**
 * Follows `Settings.brightness` and `Settings.autoBrightness`: with auto on and a light sensor, [AutoBrightness] drives the screen;
 * otherwise the fixed level does (ShellyElevate does the same: without a light sensor the fixed level applies).
 */
internal class BrightnessControl(private val settings: Settings, private val presence: Presence, private val screen: Screen) {
    internal var scheduler: Scheduler? = null
    private val auto = AutoBrightness(presence.lux, screen, settings.minBrightness, settings.lastAutoLevel)
    private val cancels = ArrayList<Cancel>()

    fun start() {
        auto.scheduler = scheduler
        cancels += settings.autoBrightness.listen { apply() }
        cancels += settings.brightness.listen { if (!usesAuto()) screen.brightness(screen.panelLevel(it)) }
        cancels += screen.state.listen { if (it == ScreenState.On && !usesAuto()) screen.brightness(screen.panelLevel(settings.brightness.value)) }
        apply()
    }

    fun stop() {
        cancels.forEach { it() }
        cancels.clear()
        auto.stop()
    }

    private fun usesAuto() = settings.autoBrightness.value && presence.hasLightSensor

    private fun apply() {
        if (usesAuto()) {
            auto.start()
        } else {
            auto.stop()
            screen.brightness(screen.panelLevel(settings.brightness.value))
        }
    }
}

/** Binds `KioskState.link` to the runtime link once the client exists (it is created when the server is known). */
internal class LinkBinder(private val state: KioskState) {
    private var bound = false

    fun bindIfPossible() {
        if (bound) return
        val client = try {
            if (Settings.server.value.isNullOrBlank()) {
                state.link.set("Not provisioned")
                return
            }
            Hashsome.client
        } catch (e: Throwable) {
            return
        }
        bound = true
        client.link.listenNow { state.link.set(it.name) }
    }
}

/** Whether the dashboard has to be started to be in front. Starting it when it already is only makes the host re-resume (and, on a bad day, rebuild). */
internal object FrontDecision {
    /**
     * [dashboardResumed]: our activity is the resumed one, so nothing is in front of it and no launch is needed. Otherwise [top] (the
     * package of the last activity moved to the foreground, null when unknown) decides, and without it [ownProcessVisible].
     */
    fun shouldLaunch(top: String?, ownPackage: String, dashboardResumed: Boolean, ownProcessVisible: () -> Boolean): Boolean {
        if (dashboardResumed) return false
        val front = if (top != null) top == ownPackage else ownProcessVisible()
        return !front
    }
}

/**
 * Checks every 2 s whether the dashboard is the app in front and relaunches it if not (the stock Shelly app pops up over
 * ours now and then). Through `UsageStatsManager` when `GET_USAGE_STATS` was granted (`appops set ... GET_USAGE_STATS allow`),
 * otherwise by our own process importance: with a visible activity it is FOREGROUND, with only the service it is above that.
 */
internal class KeepInFront(private val context: Context) {
    private var lastTop: String? = null
    private var lastQueryEnd = 0L
    private var lastLaunch = Long.MIN_VALUE / 2

    fun check() {
        val top = if (Kiosk.dashboardResumed) null else topPackage()
        if (!FrontDecision.shouldLaunch(top, context.packageName, Kiosk.dashboardResumed) { ownProcessVisible() }) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLaunch < RELAUNCH_COOLDOWN_MS) return
        lastLaunch = now
        Log.i("front", "top is ${top ?: "unknown"}, bringing the dashboard back")
        try {
            context.startActivity(dashboardIntent(context, Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w("front", "could not relaunch: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun usageAllowed(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun topPackage(): String? {
        if (!usageAllowed()) return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastQueryEnd == 0L) now - INITIAL_WINDOW_MS else lastQueryEnd - OVERLAP_MS
        val events = try {
            usage.queryEvents(from, now)
        } catch (e: SecurityException) {
            return null
        }
        val event = UsageEvents.Event()
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastTop = event.packageName
        }
        lastQueryEnd = now
        return lastTop
    }

    private fun ownProcessVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private companion object {
        const val INITIAL_WINDOW_MS = 10 * 60_000L
        const val OVERLAP_MS = 2_000L
        const val RELAUNCH_COOLDOWN_MS = 4_000L
    }
}

/** Starts [KioskService] after a reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Kiosk.startService(context)
    }
}

/**
 * Writes the extras of a `dev.hashsome.PROVISION` broadcast (server, dashboard, device, profile, mqtt) into `Settings` and restarts
 * the service. `dev.hashsome.REMOVE_ADMIN` removes the device admin, which has to happen before an uninstall (spike S1f).
 */
class ProvisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REMOVE_ADMIN -> Log.i("provision", "device admin removed: ${DeviceAdmin.remove(context)}")
            ACTION_PROVISION -> {
                val extras = PROVISION_EXTRAS.associateWith { intent.getStringExtra(it) }
                Provisioning.apply(Settings, extras)
                Settings.lastProvision = Provisioning.encode(extras)
                intent.getStringExtra("home")?.let { HomeAlias.setEnabled(context, it == "true") }
                Log.i("provision", "provisioned: ${extras.filterValues { it != null }}")
                // New stores are live at once (the bridge and the host listen); a fresh task makes the host read the server again.
                Kiosk.startService(context)
                try {
                    context.startActivity(dashboardIntent(context, Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } catch (e: Exception) {
                    Log.w("provision", "could not restart the dashboard: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val ACTION_PROVISION = "dev.hashsome.PROVISION"
        const val ACTION_REMOVE_ADMIN = "dev.hashsome.REMOVE_ADMIN"
        val PROVISION_EXTRAS = listOf("server", "dashboard", "device", "profile", "mqtt")
    }
}

/** What a provisioning broadcast does to [Settings], on plain values so it can be tested. */
internal object Provisioning {
    /** Applies the extras that are present; absent ones leave their setting alone. */
    fun apply(settings: Settings, extras: Map<String, String?>) {
        extras["server"]?.let { settings.server.set(it.trim().ifEmpty { null }) }
        extras["dashboard"]?.let { settings.dashboard.set(it.trim().ifEmpty { null }) }
        extras["device"]?.let { id -> id.trim().ifEmpty { null }?.let(settings.deviceId::set) }
        // "auto" (or empty) goes back to detecting the profile.
        extras["profile"]?.let { settings.profileOverride.set(it.trim().takeIf { p -> p.isNotEmpty() && p != "auto" }) }
        extras["mqtt"]?.let { value ->
            val text = value.trim()
            if (text.isEmpty() || text == "off") {
                settings.mqtt.enabled.set(false)
            } else {
                val host = text.substringBeforeLast(':')
                val port = if (text.contains(':')) text.substringAfterLast(':').toIntOrNull() else null
                settings.mqtt.host.set(if (port != null) host else text)
                settings.mqtt.port.set(port ?: 1883)
                settings.mqtt.enabled.set(true)
            }
        }
    }

    fun encode(extras: Map<String, String?>): String =
        JSONObject().also { json -> extras.forEach { (k, v) -> if (v != null) json.put(k, v) } }.toString()

    /** The extras of the last broadcast, as [encode] stored them. */
    fun decode(text: String?): Map<String, String?> {
        if (text == null) return emptyMap()
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.getString(it) }
    }
}

/** Device admin with one policy, `force-lock`, so a screen can be truly switched off where the device has no device owner of ours. */
class HashsomeDeviceAdmin : DeviceAdminReceiver()

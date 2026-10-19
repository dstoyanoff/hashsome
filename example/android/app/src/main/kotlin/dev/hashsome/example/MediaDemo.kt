package dev.hashsome.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.view.Choreographer
import dev.hashsome.client.EntityRef
import dev.hashsome.ui.Align
import dev.hashsome.ui.Dashboard
import dev.hashsome.ui.Hashsome
import dev.hashsome.ui.Motion
import dev.hashsome.ui.PageScope
import dev.hashsome.ui.RowSpan
import dev.hashsome.ui.ShellScope
import dev.hashsome.ui.icons.Ui

/**
 * Every media component in every state the mock backend (`pnpm --filter @hashsome/example dev`) can produce: a player that plays
 * (`ma:living_room`), one that is paused and muted (`ma:kitchen`), one that is idle (`ma:office`), an unavailable one (`ma:garage`) and one
 * that does not exist. Nothing here sends a command to a real player: the server is given by `--es server <host:port>`.
 */
object MediaDemo : Dashboard("media", motion = Motion.Light, idleReturn = false) {
    private const val LIVING = "ma:living_room"
    private const val KITCHEN = "ma:kitchen"
    private const val OFFICE = "ma:office"
    private const val GARAGE = "ma:garage"
    private const val NOWHERE = "ma:nowhere"
    private val SPEAKERS = listOf(EntityRef(KITCHEN), EntityRef(OFFICE), EntityRef(GARAGE))

    override val pages = listOf(
        page("", "Bars", Ui.music) { bars() },
        page("states", "States", Ui.speaker) { states() },
        page("columns", "Columns", Ui.library) { columns() },
        page("board", "Board", Ui.house) { board() },
        page("full", "Full", Ui.listMusic) { mediaPlayerFull(LIVING, "Living room", SPEAKERS) },
        page("lists", "Lists", Ui.list) { lists() },
    )

    override fun shell(s: ShellScope) {
        s.navRail(pages)
    }

    private fun PageScope.bars() {
        column(gap = 3, grow = true) {
            mediaPlayerBar(LIVING, "Living room", rows = 1, speakers = SPEAKERS)
            // How a small display sets it up.
            mediaPlayerBar(KITCHEN, "Kitchen", rows = 1, time = false, shuffle = false, browse = false, grouping = false)
            mediaPlayerBar(LIVING, "Living room", rows = 2, speakers = SPEAKERS)
            spacer(grow = true)
        }
    }

    private fun PageScope.states() {
        column(gap = 3, grow = true) {
            mediaPlayerBar(OFFICE, "Office", rows = 1)
            mediaPlayerBar(GARAGE, "Garage", rows = 1)
            mediaPlayerBar(NOWHERE, "Nowhere", rows = 1)
            mediaPlayerBar(KITCHEN, "Kitchen", rows = 2)
            spacer(grow = true)
        }
    }

    private fun PageScope.columns() {
        row(gap = 3, grow = true, align = Align.Stretch) {
            mediaPlayerColumn(LIVING, "Living room", overlays = true, speakers = SPEAKERS)
            mediaPlayerColumn(OFFICE, "Office", overlays = true, speakers = SPEAKERS)
            mediaPlayerColumn(GARAGE, "Garage", overlays = true)
        }
    }

    /** The bathroom's home page: the room beside the player, which opens its library as a panel over the page. */
    private fun PageScope.board() {
        board {
            cell(cols = 7) {
                mediaPlayerBar(KITCHEN, "Kitchen", rows = 1, time = false, shuffle = false, browse = false, grouping = false)
            }
            cell(cols = 5, rows = RowSpan.Fill) {
                // Without panels of its own for the library and the queue: one key opens the whole player.
                mediaPlayerColumn(KITCHEN, "Kitchen", overlays = false, speakers = SPEAKERS)
            }
        }
    }

    private fun PageScope.lists() {
        row(gap = 5, grow = true, align = Align.Stretch) {
            mediaBrowser(LIVING)
            mediaQueue(LIVING)
            mediaSpeakers(LIVING, SPEAKERS)
        }
    }
}

/**
 * Takes `--es server host:port` and `--es dashboard <id>` from the intent that starts the app, and keeps them for the next start, as a kiosk
 * is provisioned: the example is a plain app with no kiosk module, so this stands in for it.
 */
object DemoLaunch {
    fun install(app: Application) {
        val prefs = app.getSharedPreferences("demo", Context.MODE_PRIVATE)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                val intent = activity.intent
                intent?.getStringExtra("server")?.let { prefs.edit().putString("server", it).apply() }
                intent?.getStringExtra("dashboard")?.let { prefs.edit().putString("dashboard", it).apply() }
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

/**
 * Frame gaps, the way the POC measured them: a frame loop that never stops, and the time between one frame and the next. `adb shell am
 * broadcast -a dev.hashsome.example.PERF_START` begins, `PERF_STOP` says (in `Log.e`, tag `Perf`) how many frames there were, the longest
 * gap and every gap over 20 ms. A gap over 20 ms is a frame the main thread was too busy for.
 */
object FrameGaps {
    private var last = 0L
    private var gaps = ArrayList<Long>()
    private var frames = 0
    private var at = ArrayList<Int>()
    private var running = false
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last != 0L) {
                val gap = (frameTimeNanos - last) / 1_000_000
                gaps += gap
                if (gap > 20) at += frames
            }
            last = frameTimeNanos
            frames++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val lines = ArrayList<String>()
    private val samples = HashMap<String, Int>()
    private var sampler: Thread? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                activity.window.addOnFrameMetricsAvailableListener({ _, m, _ ->
                    if (!running) return@addOnFrameMetricsAvailableListener
                    val total = m.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000
                    if (total > 16) {
                        val at = m.getMetric(android.view.FrameMetrics.VSYNC_TIMESTAMP) / 1_000_000
                        fun ms(metric: Int) = m.getMetric(metric) / 1_000_000
                        lines += "frame@" + at + " total=" + total + " input=" + ms(android.view.FrameMetrics.INPUT_HANDLING_DURATION) +
                            " anim=" + ms(android.view.FrameMetrics.ANIMATION_DURATION) + " layout=" + ms(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) +
                            " draw=" + ms(android.view.FrameMetrics.DRAW_DURATION) + " sync=" + ms(android.view.FrameMetrics.SYNC_DURATION) +
                            " cmd=" + ms(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) + " swap=" + ms(android.view.FrameMetrics.SWAP_BUFFERS_DURATION) +
                            " unk=" + ms(android.view.FrameMetrics.UNKNOWN_DELAY_DURATION)
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action?.endsWith("PERF_START") == true) {
                        gaps = ArrayList()
                        lines.clear()
                        samples.clear()
                        val main = Looper.getMainLooper().thread
                        sampler = Thread {
                            while (running) {
                                val st = main.stackTrace
                                // The innermost frames of ours, and what the platform was doing under them.
                                val ours = st.firstOrNull { it.className.startsWith("dev.hashsome") }
                                val top = st.take(3).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
                                val key = (ours?.let { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: "-") + " | " + top
                                synchronized(samples) { samples[key] = (samples[key] ?: 0) + 1 }
                                Thread.sleep(2)
                            }
                        }.also { it.start() }
                        at = ArrayList()
                        frames = 0
                        last = 0L
                        running = true
                        Choreographer.getInstance().postFrameCallback(callback)
                    } else {
                        running = false
                        val slow = gaps.filter { it > 20 }
                        lines.forEach { android.util.Log.e("Perf", it) }
                        synchronized(samples) { samples.entries.sortedByDescending { it.value }.take(30).forEach { android.util.Log.e("Perf", "S " + it.value + " " + it.key) } }
                        android.util.Log.e("Perf", "frames=" + frames + " max=" + (gaps.maxOrNull() ?: 0) + "ms over20=" + slow.size + " median=" + (gaps.sorted().getOrNull(gaps.size / 2) ?: 0) + " gaps=" + slow + " at(frame)=" + at)
                    }
                }
            },
            IntentFilter().apply {
                addAction("dev.hashsome.example.PERF_START")
                addAction("dev.hashsome.example.PERF_STOP")
            },
        )
    }
}

package dev.hashsome.example

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import android.view.Choreographer

/**
 * Frame gaps, the way the POC measured them: a frame loop that never stops, and the time between one frame and the next. `adb shell am
 * broadcast -a dev.hashsome.example.PERF_START` begins, `PERF_STOP` says (in `Log.e`, tag `Perf`) how many frames there were, the longest
 * gap and every gap over 20 ms. A gap over 20 ms is a frame the main thread was too busy for. Only a debuggable build, or one started with `--ez perf true`, listens.
 */
object FrameGaps {
    private var last = 0L
    private var gaps = ArrayList<Long>()
    private var frames = 0
    private var running = false
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last != 0L) gaps += (frameTimeNanos - last) / 1_000_000
            last = frameTimeNanos
            frames++
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(app: Application) {
        // Only where it was asked for: a debuggable build, or one started once with `--ez perf true`.
        val asked = app.getSharedPreferences("demo", Context.MODE_PRIVATE).getBoolean("perf", false)
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && !asked) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action?.endsWith("PERF_START") == true) {
                    // A second start while one is running changes nothing.
                    if (running) return
                    gaps = ArrayList()
                    frames = 0
                    last = 0L
                    running = true
                    Choreographer.getInstance().postFrameCallback(callback)
                } else {
                    running = false
                    val slow = gaps.filter { it > 20 }
                    Log.e("Perf", "frames=" + frames + " max=" + (gaps.maxOrNull() ?: 0) + "ms over20=" + slow.size + " gaps=" + slow)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("dev.hashsome.example.PERF_START")
            addAction("dev.hashsome.example.PERF_STOP")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else app.registerReceiver(receiver, filter)
    }
}

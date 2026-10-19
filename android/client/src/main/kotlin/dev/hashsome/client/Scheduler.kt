package dev.hashsome.client

import android.os.Handler
import android.os.SystemClock

/**
 * Time and the main thread, behind an interface so that tests (and the headless layer) never
 * touch a real clock. Every callback the client delivers goes through [post].
 */
interface Scheduler {
    /** Runs [fn] on the main thread, soon. */
    fun post(fn: () -> Unit)

    /** Runs [fn] once, [ms] from now, unless cancelled first. */
    fun after(ms: Long, fn: () -> Unit): Cancel

    /** Runs [fn] every [ms], the first time [ms] from now, until cancelled. */
    fun every(ms: Long, fn: () -> Unit): Cancel

    /** A monotonic clock in ms (not wall time): only differences mean anything. */
    fun now(): Long
}

/** The real [Scheduler], on the thread of [handler] (the main looper's, in the app). */
class AndroidScheduler(private val handler: Handler) : Scheduler {
    override fun post(fn: () -> Unit) {
        handler.post(fn)
    }

    override fun after(ms: Long, fn: () -> Unit): Cancel {
        val task = Runnable { fn() }
        handler.postDelayed(task, ms)
        return { handler.removeCallbacks(task) }
    }

    override fun every(ms: Long, fn: () -> Unit): Cancel {
        val task = object : Runnable {
            override fun run() {
                fn()
                handler.postDelayed(this, ms)
            }
        }
        handler.postDelayed(task, ms)
        return { handler.removeCallbacks(task) }
    }

    override fun now(): Long = SystemClock.uptimeMillis()
}

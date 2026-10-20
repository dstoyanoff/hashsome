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

    /**
     * A monotonic clock in ms (not wall time): only differences mean anything. Use it for durations and timeouts
     * (heartbeats, pending feedback, idle return).
     */
    fun now(): Long

    /**
     * Wall-clock time in ms since the epoch. Use it for anything that is compared with a time somebody else
     * stamped or with the time of day: a media position from `positionUpdatedAt`, `ThemeSchedule.Clock`, the date
     * and clock chips. It can jump (the clock is set), so never time a duration with it.
     */
    fun wallMs(): Long
}

/**
 * Repeats [fn] every [ms] on top of a one-shot [postDelayed] (which returns how to cancel it). The next run is
 * posted after [fn] returns, so a slow [fn] pushes the schedule back instead of piling up, at the price of drifting
 * by its duration (a `Handler` posting at fixed ticks would not drift). Cancelling from inside [fn] stops the loop.
 */
internal fun repeatEvery(ms: Long, postDelayed: (Long, () -> Unit) -> Cancel, fn: () -> Unit): Cancel {
    var live = true
    var pending: Cancel = {}
    lateinit var tick: () -> Unit
    tick = {
        if (live) {
            fn()
            // While `fn` runs nothing is queued, so a cancel from inside it has nothing to remove: the flag stops the re-post.
            if (live) pending = postDelayed(ms, tick)
        }
    }
    pending = postDelayed(ms, tick)
    return {
        live = false
        pending()
    }
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

    override fun every(ms: Long, fn: () -> Unit): Cancel = repeatEvery(ms, ::after, fn)

    override fun now(): Long = SystemClock.uptimeMillis()

    override fun wallMs(): Long = System.currentTimeMillis()
}

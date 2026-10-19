package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler

/** Whether it is time to go back: nothing has been touched for [after] ms (`shouldLeave`). */
fun shouldLeave(now: Long, lastTouched: Long, after: Long): Boolean = now - lastTouched >= after

/**
 * Takes a display back to its home page after [afterMs] without a touch (mirrors `use-idle-return.ts`).
 * Idle time only counts away from home: [arrivedAt] says which page is showing.
 */
class IdleReturn(private val scheduler: Scheduler, private val afterMs: Long, private val onReturn: () -> Unit) {
    private var lastTouched = 0L
    private var check: Cancel? = null

    /** Any touch: restarts the countdown. */
    fun touched() {
        lastTouched = scheduler.now()
    }

    /** A page was shown; [isHome] stops the countdown, any other page starts it. */
    fun arrivedAt(page: String, isHome: Boolean) {
        if (isHome || afterMs <= 0) {
            stop()
            return
        }

        // Already counting on another page: the time runs on from the last touch, as it does on the web.
        if (check != null) return
        lastTouched = scheduler.now()
        check = scheduler.every(Math.max(1L, Math.min(CHECK_MS, afterMs / 2))) {
            if (shouldLeave(scheduler.now(), lastTouched, afterMs)) {
                // Once is enough: showing the home page is what ends the countdown, and starting it again is [arrivedAt]'s.
                stop()
                onReturn()
            }
        }
    }

    fun stop() {
        check?.invoke()
        check = null
    }

    companion object {
        /** How often the page checks whether it has been left alone long enough, at most. */
        const val CHECK_MS = 10_000L
    }
}

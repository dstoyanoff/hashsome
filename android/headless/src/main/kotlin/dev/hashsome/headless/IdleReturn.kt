package dev.hashsome.headless

import dev.hashsome.client.Scheduler

/**
 * Takes a display back to its home page after [afterMs] without a touch (mirrors `use-idle-return.ts`).
 * Idle time only counts away from home: [arrivedAt] says which page is showing.
 */
class IdleReturn(private val scheduler: Scheduler, private val afterMs: Long, private val onReturn: () -> Unit) {
    /** Any touch: restarts the countdown. */
    fun touched(): Unit = TODO("WP2")

    /** A page was shown; [isHome] stops the countdown, any other page starts it. */
    fun arrivedAt(page: String, isHome: Boolean): Unit = TODO("WP2")

    fun stop(): Unit = TODO("WP2")
}

package dev.hashsome.testing

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler

/**
 * A [Scheduler] with a clock that only moves when a test says so, so a 15 s heartbeat or a 55 ms
 * stagger is checked in microseconds and always in the same order.
 *
 * Nothing runs on its own: [post] queues for the current time and runs at the next [runCurrent] or
 * [advance]. Tasks due at the same time run in the order they were scheduled. A task may schedule
 * or cancel others, including ones due right now, which run in the same [advance].
 *
 * [advance] is not re-entrant (a task calling it throws); a task that throws leaves the clock at that
 * task's time and the rest queued, so the next [advance] carries on.
 */
class TestScheduler : Scheduler {
    private class Task(val at: Long, val seq: Long, val fn: () -> Unit) {
        var cancelled = false
    }

    private val queue = ArrayList<Task>()
    private var seq = 0L
    private var clock = 0L
    private var advancing = false

    /** What [wallMs] says: set it to start at a given time (or date), and [advance] moves it along with [now]. */
    var wall: Long = 0L

    override fun now(): Long = clock

    override fun wallMs(): Long = wall

    override fun post(fn: () -> Unit) {
        enqueue(clock, fn)
    }

    override fun after(ms: Long, fn: () -> Unit): Cancel {
        val task = enqueue(clock + ms, fn)
        return { task.cancelled = true }
    }

    override fun every(ms: Long, fn: () -> Unit): Cancel {
        require(ms > 0) { "every() needs a positive period, or advance() would never finish" }
        var current: Task? = null
        var stopped = false
        fun schedule() {
            current = enqueue(clock + ms) {
                if (!stopped) {
                    schedule()
                    fn()
                }
            }
        }
        schedule()
        return {
            stopped = true
            current?.cancelled = true
        }
    }

    /** Runs what is due now (including what that schedules for now), without moving the clock. */
    fun runCurrent() = advance(0)

    /** Moves the clock forward by [ms], running every task that falls due, in time order, with [now] and [wallMs] at each task's time. */
    fun advance(ms: Long) {
        require(ms >= 0) { "time does not go back" }
        check(!advancing) { "advance() is not re-entrant: a task cannot move the clock" }
        advancing = true
        try {
            val target = clock + ms
            while (true) {
                val next = queue.filter { !it.cancelled && it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                queue.remove(next)
                moveTo(maxOf(clock, next.at))
                next.fn()
            }
            moveTo(target)
            queue.removeAll { it.cancelled }
        } finally {
            advancing = false
        }
    }

    /** How many tasks are waiting, cancelled ones not counted. */
    val pending: Int get() = queue.count { !it.cancelled }

    private fun moveTo(time: Long) {
        wall += time - clock
        clock = time
    }

    private fun enqueue(at: Long, fn: () -> Unit): Task = Task(at, seq++, fn).also { queue += it }
}

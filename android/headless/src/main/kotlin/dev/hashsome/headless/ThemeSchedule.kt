package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import java.util.Calendar
import java.util.TimeZone

/** When the dark theme is on (mirrors `theme/schedule.ts`). */
sealed class ThemeSchedule {
    /** Dark while the `daylight` sensor [ref] (the sun entity) is off. */
    data class Sun(val ref: EntityRef) : ThemeSchedule()

    /** Dark between two times of day on the display's own clock, `HH:MM`. [to] may be earlier than [from] (the next morning); the same time twice means never dark. */
    data class Clock(val from: String, val to: String) : ThemeSchedule()

    /** Always dark, or always light. */
    data class Fixed(val dark: Boolean) : ThemeSchedule()
}

private val TIME = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$")

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Minutes after midnight for `HH:MM`. Throws on anything else, naming what was wrong ([name] is `from` or `to`). */
fun minutesOf(time: String, name: String): Int {
    val match = TIME.matchEntire(time) ?: throw IllegalArgumentException("theme.dark.$name must be a time like \"19:00\", not \"$time\"")
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

private fun calendarAt(epochMs: Long, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply { timeInMillis = epochMs }

/** Whether [epochMs] is inside the dark hours of [range], on the clock of [zone]. */
fun isDarkAt(range: ThemeSchedule.Clock, epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
    val from = minutesOf(range.from, "from")
    val to = minutesOf(range.to, "to")
    val calendar = calendarAt(epochMs, zone)
    val at = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    if (from == to) return false
    // Dark hours that run past midnight are everything except the daytime between `to` and `from`.
    return if (from < to) at in from until to else at >= from || at < to
}

/** How long until the theme next changes, in ms; null when it never does (the same time twice). */
fun msUntilSwitch(range: ThemeSchedule.Clock, epochMs: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
    val from = minutesOf(range.from, "from")
    val to = minutesOf(range.to, "to")
    if (from == to) return null

    fun wait(minutes: Int): Long {
        val at = calendarAt(epochMs, zone).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val ms = at.timeInMillis - epochMs
        return if (ms > 0) ms else ms + DAY_MS
    }

    return Math.min(wait(from), wait(to))
}

/**
 * What a `daylight` sensor says (`on` while the sun is up): true once it is `off`, false while `on`. Null for anything
 * else (not loaded yet, unavailable, not that kind of sensor).
 */
fun sunIsDown(entity: Entity?): Boolean? {
    if (entity !is SensorEntity || entity.measurement != "daylight") return null
    return when (entity.value) {
        "off" -> true
        "on" -> false
        else -> null
    }
}

/**
 * Follows a [ThemeSchedule] and says whether the dark theme is on right now. A sun schedule keeps the last answer the
 * sun gave while it says nothing useful, and starts light until it has given one. A clock schedule is read again a
 * moment past each boundary, and on [recheck] (call it when the screen comes back, since a sleeping display misses timers).
 */
class ThemeController(val schedule: ThemeSchedule, private val scope: Scope) {
    val dark: Store<Boolean> = Store(schedule is ThemeSchedule.Fixed && schedule.dark)

    private var timer: Cancel? = null

    init {
        when (schedule) {
            is ThemeSchedule.Fixed -> Unit
            is ThemeSchedule.Sun -> scope.watch(schedule.ref) { snapshot ->
                val down = sunIsDown((snapshot as? Snapshot.Present)?.entity)
                if (down != null) dark.set(down)
            }

            is ThemeSchedule.Clock -> {
                // A time that is not HH:MM is refused now, not at the first boundary.
                minutesOf(schedule.from, "from")
                minutesOf(schedule.to, "to")
                recheck()
            }
        }
    }

    /** Reads the clock again and sets the timer for the next boundary. Does nothing for a schedule that is not a clock. */
    fun recheck() {
        val clock = schedule as? ThemeSchedule.Clock ?: return
        timer?.invoke()
        timer = null
        val now = scope.scheduler.wallMs()
        dark.set(isDarkAt(clock, now))
        val wait = msUntilSwitch(clock, now) ?: return
        // A moment past the boundary, so the clock has certainly crossed it.
        timer = scope.after(wait + BOUNDARY_SLACK_MS) {
            timer = null
            recheck()
        }
    }

    companion object {
        // A sun schedule starts light until the sun answers; the web starts from the last answer it remembered, or the system. Persisting it is for the host.
        const val BOUNDARY_SLACK_MS = 500L
    }
}

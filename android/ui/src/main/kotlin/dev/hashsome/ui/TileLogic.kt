package dev.hashsome.ui

import dev.hashsome.client.Availability
import dev.hashsome.client.Entity
import dev.hashsome.client.EntityRef
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import dev.hashsome.headless.Scope
import dev.hashsome.client.ForecastPoint
import dev.hashsome.client.Iso8601
import dev.hashsome.headless.HandleState
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// The words and numbers the tiles and the top row show, as pure functions (no `android.*`), so they are tested on the JVM.
// Each mirrors the web component it is named after.

/** What a tile says in place of its reading while its entity is not usable (the web's `statusLabels`); null while it is ready. */
object StatusLabels {
    fun of(state: HandleState<*>): String? = when (state) {
        HandleState.Loading -> "…"
        HandleState.Missing -> "Not found"
        HandleState.Unsupported -> "Unsupported"
        is HandleState.Unavailable -> if (state.entity?.availability == Availability.Unknown) "Unknown" else "Unavailable"
        is HandleState.Ready -> null
    }
}

/**
 * The status of an entity of any kind, as the web's `status?: EntityStatus` of a tile: ready, unavailable (or not reported on yet),
 * missing or loading, from whatever entity is there. A component that only needs to say why it is disabled takes this instead of a
 * handle of one kind, so a `binary_sensor` (the sensor kind) or a `calendar` is as good as an action.
 */
object EntityStatus {
    /** What [snapshot] means for a component: never [HandleState.Unsupported], since no kind is asked for. */
    fun of(snapshot: Snapshot): HandleState<Entity> = when (snapshot) {
        Snapshot.Loading -> HandleState.Loading
        Snapshot.Missing -> HandleState.Missing
        is Snapshot.Present ->
            if (snapshot.entity.availability == Availability.Ready) HandleState.Ready(snapshot.entity) else HandleState.Unavailable(snapshot.entity)
    }

    /** The status of [ref] while [scope] lives; [HandleState.Missing] when there is no ref. */
    fun watch(scope: Scope, ref: String?): Store<HandleState<Entity>> {
        val store = Store<HandleState<Entity>>(if (ref == null) HandleState.Missing else HandleState.Loading)
        if (ref != null) scope.watch(EntityRef(ref)) { store.set(of(it)) }
        return store
    }
}

/** JavaScript's `Math.round`: halves go up. */
internal fun roundHalfUp(x: Double): Int = Math.floor(x + 0.5).toInt()

/** The pieces of the light tile's second line. */
object LightTileText {
    /** "Off" while off, the level while on and dimmable (and known), nothing for a light that cannot dim. */
    fun secondary(on: Boolean, dimmable: Boolean, percent: Int?): String? = when {
        !on -> "Off"
        dimmable && percent != null -> "$percent%"
        else -> null
    }
}

/** JavaScript's `Number.prototype.toFixed`: [value]'s exact binary value rounded to [digits] decimals, ties away from zero. */
fun toFixed(value: Double, digits: Int): String = java.math.BigDecimal(value).setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()

/** A switch's draw, as the second line of its tile: one decimal and the unit (`toFixed(1)`). */
fun wattsText(watts: Double): String = toFixed(watts, 1) + " W"

/** How many decimals a number is written with: `21.5` has one, `21.0` none (the web's `decimalsOf`). */
fun decimalsOf(value: Double): Int =
    if (value.isFinite()) java.math.BigDecimal(value.toString()).stripTrailingZeros().scale().coerceAtLeast(0) else 0

/** The climate tile's second line: the room's temperature with the decimals it has (`21 °C`, `21.5 °C`). */
fun climateReading(current: Double, unit: String): String = "${toFixed(current, decimalsOf(current))} $unit"

/** The time and date as the top row writes them. */
object WallClockText {
    /** "Tue · Oct 6". */
    fun date(wallMs: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val weekday = java.text.SimpleDateFormat("EEE", locale).apply { timeZone = zone }.format(java.util.Date(wallMs))
        val day = java.text.SimpleDateFormat("MMM d", locale).apply { timeZone = zone }.format(java.util.Date(wallMs))
        return "$weekday · $day"
    }

    /** The time and, in a 12 hour clock, the day period beside it (`3:07` and `PM`). */
    data class Clock(val time: String, val period: String?)

    fun clock(wallMs: Long, is24Hour: Boolean, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): Clock {
        val c = Calendar.getInstance(zone, locale).apply { timeInMillis = wallMs }
        val minute = String.format(Locale.US, "%02d", c.get(Calendar.MINUTE))
        if (is24Hour) return Clock(String.format(Locale.US, "%02d:%s", c.get(Calendar.HOUR_OF_DAY), minute), null)
        val hour = c.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        return Clock("$hour:$minute", if (c.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
    }

    /** Milliseconds from [wallMs] to the next minute's start (plus a little), so a clock changes when the minute does. */
    fun untilNextMinute(wallMs: Long): Long = 60_000L - (wallMs % 60_000L) + 50
}

/** Today's high and low under the weather reading (`12° / 4°`). */
object WeatherRange {
    /** The point for today (the day of [nowMs] in [zone]) else the first; null when there are none or it has no low. */
    fun of(points: List<ForecastPoint>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        val today = points.firstOrNull { p -> Iso8601.parse(p.timestamp)?.let { sameDay(it, nowMs, zone) } == true } ?: points.firstOrNull()
        val high = today?.temperature ?: return null
        val low = today.low ?: return null
        return "${roundHalfUp(high)}° / ${roundHalfUp(low)}°"
    }

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean = TaskText.startOfDay(a, zone) == TaskText.startOfDay(b, zone)
}

/** Where a recurring task stands: not due for a while, due before long, or past its date. */
enum class TaskState { Ok, DueSoon, Overdue }

/** What a task tile shows about its task. Whoever has the task decides the state (how soon is "soon" differs by task). */
data class TaskInfo(
    val state: TaskState = TaskState.Ok,
    /** When it is next due, epoch ms. */
    val dueAt: Long? = null,
    /** When it was last done, epoch ms; shown in the question that marks it done again. */
    val lastDoneAt: Long? = null,
)

/** The words of the task tile and its question (the web's `taskLine`, `short` and the day stepper). */
object TaskText {
    const val DAY_MS = 86_400_000L

    fun startOfDay(ms: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = ms
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Calendar days from [now] to [then]: negative when [then] has passed. */
    fun daysTo(then: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): Int =
        Math.round((startOfDay(then, zone) - startOfDay(now, zone)).toDouble() / DAY_MS).toInt()

    private fun plural(n: Int) = "$n day${if (n == 1) "" else "s"}"

    /** A day as "Mar 10", with the year ("Mar 10, 2027") when it is not this year's. */
    fun short(date: Long, now: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val year = { ms: Long -> Calendar.getInstance(zone).apply { timeInMillis = ms }.get(Calendar.YEAR) }
        val pattern = if (year(date) == year(now)) "MMM d" else "MMM d, yyyy"
        return java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(java.util.Date(date))
    }

    /** The line under a task's name. */
    fun line(info: TaskInfo, now: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val due = info.dueAt
        if (due == null) {
            return when (info.state) {
                TaskState.Overdue -> "Overdue"
                TaskState.DueSoon -> "Due soon"
                TaskState.Ok -> "Up to date"
            }
        }

        if (info.state == TaskState.Ok) return "Next ${short(due, now, locale, zone)}"
        val days = daysTo(due, now, zone)
        return when {
            days == 0 -> "Due today"
            days > 0 -> if (days == 1) "Due tomorrow" else "Due in ${plural(days)}"
            else -> "Overdue by ${plural(-days)}"
        }
    }

    /** The day the stepper names, [daysAgo] before [now]: "Today", "Yesterday", else "Mon, Mar 9". */
    fun dayWords(daysAgo: Int, now: Long, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        if (daysAgo == 0) return "Today"
        if (daysAgo == 1) return "Yesterday"
        val day = java.util.Date(now - daysAgo * DAY_MS)
        return java.text.SimpleDateFormat("EEE, MMM d", locale).apply { timeZone = zone }.format(day)
    }

    /** The moment a task done [daysAgo] days ago counts as done: now for today, else the start of that day. */
    fun doneOn(daysAgo: Int, now: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        if (daysAgo == 0) now else startOfDay(now - daysAgo * DAY_MS, zone)
}

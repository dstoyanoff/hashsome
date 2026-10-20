package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.Iso8601
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.Store
import java.math.BigDecimal

/** A sensor's battery, as a percentage. */
data class Battery(val percent: Int, val low: Boolean)

/** What a sensor readout shows: the value as text with its unit, its battery, and whether the reading is outside a range worth a warning. */
data class Reading(
    val value: Double?,
    val unit: String?,
    /** The value formatted for display, e.g. `21.4 °C`; the state text for a non-numeric sensor. */
    val formatted: String,
    val battery: Battery?,
    /** False when the value is outside the safe range given to the control (e.g. humidity above 70). */
    val inSafeRange: Boolean,
)

/** The line under the value in a sensor's drawer: whether the reading is within its safe range, e.g. `Too low · safe range 30–60 %`. */
data class Verdict(val text: String, val warning: Boolean)

/** How long a sensor has said nothing: more than [THRESHOLD_HOURS] hours, when it is told of at all. */
data class Silence(val hours: Long) {
    /** "26 hours", or "3 days" from two days on. */
    val duration: String get() = if (hours < 48) "$hours hours" else "${hours / 24} days"

    /** "No updates for 26 hours": what the drawer's alert says. */
    val title: String get() = "No updates for $duration"

    /** What may be the matter. */
    val hint: String get() = "The sensor may be dead or stuck."

    companion object {
        const val THRESHOLD_HOURS = 24L
    }
}

/**
 * A sensor readout (mirrors `sensor-readout.tsx`). With no [safeRange] a humidity sensor is held to 30..60 and any
 * other has none; pass an unbounded range (`Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY`) to turn the default off.
 */
class SensorReading(
    val handle: EntityHandle<SensorEntity>,
    val battery: EntityHandle<SensorEntity>? = null,
    val safeRange: ClosedFloatingPointRange<Double>? = null,
) {
    val reading: Store<Reading> = Store(Reading(null, null, "", null, true))

    /** Why the reading is flagged, e.g. `Bedroom is too low (safe range 30–60 %)`; null while it is not. */
    val hint: Store<String?> = Store(null)

    /** Set while the sensor has sent no update for more than 24 hours (by its `lastUpdated`, else `lastChanged`): it may be dead or stuck. Checked again every minute. */
    val silence: Store<Silence?> = Store(null)

    /** The verdict of the sensor's drawer; null while the sensor has no safe range or no numeric reading. */
    val verdict: Store<Verdict?> = Store(null)

    init {
        handle.state.listenNow { update() }
        battery?.state?.listen { update() }
        // A sensor that has stopped reporting never changes its state: the clock has to be looked at.
        handle.scope?.every(SILENCE_CHECK_MS) { updateSilence() }
    }

    private fun update() {
        updateSilence()
        val sensor = handle.entity
        val ready = handle.state.value is HandleState.Ready
        val unit = sensor?.unit ?: ""
        val range = safeRange ?: sensor?.measurement?.let { DEFAULT_SAFE_RANGES[it] }
        val level = if (ready && sensor != null) rangeLevel(sensor.value, range) else null
        val formatted = if (ready && sensor != null) formatValue(sensor.value) + (if (unit.isNotEmpty()) " $unit" else "") else statusLabel()
        reading.set(
            Reading(
                value = if (ready) sensor?.numeric else null,
                unit = sensor?.unit,
                formatted = formatted,
                battery = batteryOf(),
                inSafeRange = level != RangeLevel.Low && level != RangeLevel.High,
            ),
        )

        verdict.set(
            if (range != null && level != null && (range.start.isFinite() || range.endInclusive.isFinite())) {
                val warning = level != RangeLevel.Ok
                Verdict(
                    if (warning) "Too ${if (level == RangeLevel.Low) "low" else "high"} · safe range ${describeRange(range, unit)}" else "Within the safe range (${describeRange(range, unit)})",
                    warning,
                )
            } else {
                null
            },
        )

        hint.set(
            if ((level == RangeLevel.Low || level == RangeLevel.High) && range != null && sensor != null) {
                "${sensor.name} is too ${if (level == RangeLevel.Low) "low" else "high"} (safe range ${describeRange(range, unit)})"
            } else {
                null
            },
        )
    }

    private fun updateSilence() {
        val sensor = handle.entity
        val at = Iso8601.parse(sensor?.lastUpdated ?: sensor?.lastChanged)
        val now = handle.scope?.scheduler?.wallMs()
        val hours = if (at == null || now == null) 0L else (now - at) / HOUR_MS
        silence.set(if (hours >= Silence.THRESHOLD_HOURS) Silence(hours) else null)
    }

    private fun batteryOf(): Battery? {
        val level = if (battery?.state?.value is HandleState.Ready) battery.entity?.numeric else null
        return level?.let { Battery(roundHalfUp(it).toInt(), it <= LOW_BATTERY) }
    }

    private fun statusLabel(): String = when (val state = handle.state.value) {
        HandleState.Loading -> "…"
        HandleState.Missing -> "Not found"
        HandleState.Unsupported -> "Unsupported"
        is HandleState.Unavailable -> if (state.entity?.availability == Availability.Unknown) "Unknown" else "Unavailable"
        is HandleState.Ready -> ""
    }

    private enum class RangeLevel { Low, High, Ok }

    private fun rangeLevel(raw: String, range: ClosedFloatingPointRange<Double>?): RangeLevel? {
        val value = raw.trim().toDoubleOrNull()
        if (range == null || value == null || !value.isFinite()) return null
        return when {
            value < range.start -> RangeLevel.Low
            value > range.endInclusive -> RangeLevel.High
            else -> RangeLevel.Ok
        }
    }

    private fun describeRange(range: ClosedFloatingPointRange<Double>, unit: String): String {
        val suffix = if (unit.isNotEmpty()) " $unit" else ""
        val low = range.start.isFinite()
        val high = range.endInclusive.isFinite()
        return when {
            low && high -> "${plain(range.start)}–${plain(range.endInclusive)}$suffix"
            low -> "at least ${plain(range.start)}$suffix"
            else -> "at most ${plain(range.endInclusive)}$suffix"
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val SILENCE_CHECK_MS = 60_000L

        /** Below this a battery is shown as low. */
        const val LOW_BATTERY = 20.0

        /** Humidity is held to 30..60 unless a range is given (as the web does); an unbounded range turns the default off, standing in for the web's `safeRange={false}`. */
        private val DEFAULT_SAFE_RANGES = mapOf("humidity" to 30.0..60.0)

        /** A reading to one decimal, as the web readout writes it: `21.4`, `21` and not `21.0`; text that is no number as it is. */
        fun formatValue(raw: String): String {
            val value = raw.trim().toDoubleOrNull()
            if (raw.trim().isEmpty() || value == null || !value.isFinite()) return raw
            return plain(Math.floor(value * 10 + 0.5) / 10)
        }

        private fun roundHalfUp(x: Double): Long = Math.floor(x + 0.5).toLong()

        /** A number as JavaScript writes it: no `.0` on a whole number, no exponent. */
        private fun plain(x: Double): String {
            val whole = if (x == 0.0) 0.0 else x
            return if (whole == Math.floor(whole) && Math.abs(whole) < 1e15) {
                whole.toLong().toString()
            } else {
                BigDecimal(whole.toString()).stripTrailingZeros().toPlainString()
            }
        }
    }
}

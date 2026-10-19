package dev.hashsome.headless

import dev.hashsome.client.Availability
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

    init {
        handle.state.listenNow { update() }
        battery?.state?.listen { update() }
    }

    private fun update() {
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

        hint.set(
            if ((level == RangeLevel.Low || level == RangeLevel.High) && range != null && sensor != null) {
                "${sensor.name} is too ${if (level == RangeLevel.Low) "low" else "high"} (safe range ${describeRange(range, unit)})"
            } else {
                null
            },
        )
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
        /** Below this a battery is shown as low. */
        const val LOW_BATTERY = 20.0

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

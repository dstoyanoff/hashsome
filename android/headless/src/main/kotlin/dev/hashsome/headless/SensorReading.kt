package dev.hashsome.headless

import dev.hashsome.client.SensorEntity
import dev.hashsome.client.Store

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

class SensorReading(
    val handle: EntityHandle<SensorEntity>,
    val battery: EntityHandle<SensorEntity>? = null,
    val safeRange: ClosedFloatingPointRange<Double>? = null,
) {
    val reading: Store<Reading> = Store(Reading(null, null, "", null, true))
}

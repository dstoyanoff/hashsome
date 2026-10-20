package dev.hashsome.headless

/**
 * The battery of a display that runs on one, as its indicator reports it: red at [LOW] percent and under, warm under [FAIR], and from there fine
 * (the indicator shows the percentage all the time, in its usual color when it is fine).
 */
data class BatteryStatus(val percent: Int, val charging: Boolean) {
    /** [HealthLevel.Down] red, [HealthLevel.Pending] warm, [HealthLevel.Ok] fine. */
    val level: HealthLevel
        get() = when {
            percent <= LOW -> HealthLevel.Down
            percent < FAIR -> HealthLevel.Pending
            else -> HealthLevel.Ok
        }

    /** "42%". */
    val text: String get() = "$percent%"

    /** "42%", or "42% · charging". */
    val label: String get() = if (charging) "$percent% · charging" else "$percent%"

    companion object {
        /** At and under this the battery is red, in percent. */
        const val LOW = 20

        /** Under this it is warm, and from it fine. */
        const val FAIR = 50
    }
}

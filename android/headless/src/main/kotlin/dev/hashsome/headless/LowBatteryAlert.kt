package dev.hashsome.headless

/**
 * When a battery that is running down should speak up: once it is under [threshold] percent and not charging, and again for every percent it drops
 * from there. Charging, or a level from the threshold up, starts it over, so the next time it falls under it says so at once.
 */
class LowBatteryAlert(private val threshold: Int = THRESHOLD) {
    private var told: Int? = null

    /** The percent to tell the person about for this reading of the battery, or null when there is nothing new to say. */
    fun next(status: BatteryStatus?): Int? {
        if (status == null || status.charging || status.percent >= threshold) {
            told = null
            return null
        }
        val before = told
        if (before != null && status.percent >= before) return null
        told = status.percent
        return status.percent
    }

    companion object {
        /** Under this, in percent, it speaks up. */
        const val THRESHOLD = 10
    }
}

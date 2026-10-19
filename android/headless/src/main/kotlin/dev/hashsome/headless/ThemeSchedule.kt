package dev.hashsome.headless

import dev.hashsome.client.EntityRef
import dev.hashsome.client.Store

/** When the dark theme is on (mirrors `theme/schedule.ts`). */
sealed class ThemeSchedule {
    /** Dark while the `daylight` sensor [ref] (the sun entity) is off. */
    data class Sun(val ref: EntityRef) : ThemeSchedule()

    /** Dark between two times of day on the display's own clock, `HH:MM`. [to] may be earlier than [from] (the next morning); the same time twice means never dark. */
    data class Clock(val from: String, val to: String) : ThemeSchedule()

    /** Always dark, or always light. */
    data class Fixed(val dark: Boolean) : ThemeSchedule()
}

/** Follows a [ThemeSchedule] and says whether the dark theme is on right now. */
class ThemeController(val schedule: ThemeSchedule, scope: Scope) {
    val dark: Store<Boolean> = Store(schedule is ThemeSchedule.Fixed && schedule.dark)
}

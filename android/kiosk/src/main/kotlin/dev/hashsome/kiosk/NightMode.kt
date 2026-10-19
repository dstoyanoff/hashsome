package dev.hashsome.kiosk

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store

sealed interface NightState {
    /** Night mode is off: the screen stays on. */
    data object Day : NightState

    /** Night mode is on and someone is here: the screen is on until [until] (a `Scheduler.now()` time) without a touch or proximity. */
    data class NightAwake(val until: Long) : NightState

    /** Night mode is on and the screen is off. */
    data object NightAsleep : NightState
}

/**
 * Follows `Settings.night` and never flips it itself (no schedule, no sun). While night: off after `Settings.nightIdleMs`
 * without a touch or proximity, woken by either. While day: always on.
 *
 * [sleep] and [wake] are the buttons of Home Assistant and the settings sheet. They work in both modes: in day mode [sleep]
 * switches the screen off until the next touch or proximity (the state stays [NightState.Day]); [wake] switches it on and,
 * at night, starts the idle timer again.
 */
class NightMode(private val settings: Settings, private val presence: Presence, private val screen: Screen, private val scheduler: Scheduler) {
    val state: Store<NightState> = Store(NightState.Day)

    /** A sleep asked for in day mode: the screen is off although the mode is [NightState.Day]. */
    private var dayAsleep = false
    private var idle: Cancel? = null
    private val cancels = ArrayList<Cancel>()

    init {
        cancels += settings.night.listen { onNightChanged(it) }
        cancels += settings.nightIdleMs.listen {
            if (state.value is NightState.NightAwake) armIdle()
        }
        cancels += presence.touches.listen { activity() }
        cancels += presence.near.listen { if (it) activity() }
        onNightChanged(settings.night.value)
    }

    /** Switches the screen on and, at night, waits `nightIdleMs` for the next touch before it goes off again. */
    fun wake() {
        dayAsleep = false
        if (screen.state.value == ScreenState.Off) screen.wake()
        if (settings.night.value) armIdle() else state.set(NightState.Day)
    }

    /** Switches the screen off now. */
    fun sleep() {
        idle?.invoke()
        idle = null
        if (settings.night.value) state.set(NightState.NightAsleep) else dayAsleep = true
        screen.off()
    }

    internal fun close() {
        cancels.forEach { it() }
        cancels.clear()
        idle?.invoke()
        idle = null
    }

    private fun onNightChanged(night: Boolean) {
        idle?.invoke()
        idle = null
        dayAsleep = false
        if (night) {
            // Switching night on counts as someone being here (it is the person at the sheet, or Home Assistant's automation at dusk).
            armIdle()
        } else {
            state.set(NightState.Day)
            if (screen.state.value == ScreenState.Off) screen.wake()
        }
    }

    /** A touch or someone near. */
    private fun activity() {
        val asleep = state.value == NightState.NightAsleep || dayAsleep
        when {
            asleep -> wake()
            state.value is NightState.NightAwake -> armIdle()
        }
    }

    private fun armIdle() {
        idle?.invoke()
        val ms = settings.nightIdleMs.value
        state.set(NightState.NightAwake(scheduler.now() + ms))
        idle = scheduler.after(ms) {
            idle = null
            state.set(NightState.NightAsleep)
            screen.off()
        }
    }
}

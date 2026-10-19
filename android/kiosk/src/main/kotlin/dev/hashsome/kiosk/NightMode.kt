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
 * without a touch or proximity (and not while someone is still near), woken by either. While day: always on, so a screen
 * that goes off by other means (the power key, the stock app's timeout) is woken at once.
 *
 * A dark panel always wakes: a touch or proximity switches it on whatever the state says. [sleep] and [wake] are the
 * buttons of Home Assistant and the settings sheet. They work in both modes: in day mode [sleep] switches the screen off
 * until the next touch or proximity (the state stays [NightState.Day]); [wake] switches it on and, at night, starts the
 * idle timer again.
 */
class NightMode(private val settings: Settings, private val presence: Presence, private val screen: Screen, private val scheduler: Scheduler) {
    val state: Store<NightState> = Store(NightState.Day)

    /** A sleep asked for in day mode: the screen is off on purpose although the mode is [NightState.Day]. */
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
        cancels += screen.state.listen { onScreen(it) }
        // Someone else lit the panel while we think it is dark (the stock app writes the X1's backlight): that is a wake. Only while asleep.
        cancels += scheduler.every(RESYNC_MS) {
            if (state.value == NightState.NightAsleep && screen.litByOthers()) wake()
        }
        onNightChanged(settings.night.value)
    }

    /** The panel is dark, by whatever means: a touch on it, or the next wake, has to be heard. */
    internal val asleep: Boolean get() = screen.state.value == ScreenState.Off || state.value == NightState.NightAsleep || dayAsleep

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
            // A screen that is already dark (night switched on while off, or the app restarted while asleep) starts asleep.
            if (screen.state.value == ScreenState.Off) state.set(NightState.NightAsleep) else armIdle()
        } else {
            state.set(NightState.Day)
            if (screen.state.value == ScreenState.Off) screen.wake()
        }
    }

    /** The screen went on or off by itself or by our own doing. */
    private fun onScreen(now: ScreenState) {
        if (now == ScreenState.Off) {
            if (settings.night.value) {
                idle?.invoke()
                idle = null
                state.set(NightState.NightAsleep)
            } else if (!dayAsleep) {
                // Day is always on: the power key or the stock app's timeout does not get to keep it dark.
                screen.wake()
            }
        } else if (settings.night.value && state.value == NightState.NightAsleep) {
            armIdle()
        }
    }

    /** A touch or someone near. */
    private fun activity() {
        when {
            screen.state.value == ScreenState.Off || state.value == NightState.NightAsleep || dayAsleep -> wake()
            state.value is NightState.NightAwake -> armIdle()
        }
    }

    private fun armIdle() {
        idle?.invoke()
        val ms = settings.nightIdleMs.value
        state.set(NightState.NightAwake(scheduler.now() + ms))
        idle = scheduler.after(ms) {
            idle = null
            // Someone still standing at the display is no reason to switch it off.
            if (presence.near.value) {
                armIdle()
            } else {
                state.set(NightState.NightAsleep)
                screen.off()
            }
        }
    }

    private companion object {
        const val RESYNC_MS = 2_000L
    }
}

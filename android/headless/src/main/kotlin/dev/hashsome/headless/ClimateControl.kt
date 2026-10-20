package dev.hashsome.headless

import dev.hashsome.client.ClimateAction
import dev.hashsome.client.ClimateCommands
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.Store

/**
 * What a climate tile shows and does (the web's `climate-tile.tsx`): the mode and the modes to pick from, the target and the current
 * temperature, the step and range, the humidity, what it is doing, and the presets. The target the person is stepping to is held ([State.shown])
 * until the device reports it, so a press is answered at once and the next press adds to it, not to the old reading.
 */
class ClimateControl(val handle: EntityHandle<ClimateEntity>) {
    /** Everything the tile draws, in one value. [shown] is the held target while one is being set, else [target]. */
    data class State(
        val mode: ClimateMode,
        /** The modes to offer: the device's own, or off and heat when it reports none. */
        val modes: List<ClimateMode>,
        /** What the device reports. */
        val target: Double?,
        val shown: Double?,
        val current: Double?,
        val humidity: Double?,
        val action: ClimateAction?,
        val preset: String?,
        val presets: List<String>,
        val step: Double,
        val min: Double,
        val max: Double,
        val unit: String,
    ) {
        /** "Heating · 16.4 °C now · 45% humidity": what the mode row of the drawer says. */
        val summary: String
            get() = listOfNotNull(
                action?.let { title(it.wire) } ?: if (mode == ClimateMode.Off) "Off" else title(mode.wire),
                current?.let { "${number(it)} $unit now" },
                humidity?.let { "${number(it)}% humidity" },
            ).joinToString(" · ")
    }

    val mode: Store<ClimateMode> = Store(ClimateMode.Off)
    val target: Store<Double?> = Store(null)
    val current: Store<Double?> = Store(null)
    val state: Store<State> = Store(blank())

    /** The target asked for and not yet reported back. */
    private var held: Double? = null

    init {
        handle.state.listenNow { refresh() }
    }

    private fun refresh() {
        val climate = handle.entity
        // The device has caught up with what was asked.
        if (held != null && held == climate?.targetTemperature) held = null
        mode.set(climate?.mode ?: ClimateMode.Off)
        target.set(climate?.targetTemperature)
        current.set(climate?.currentTemperature)
        state.set(
            if (climate == null) {
                blank()
            } else {
                val caps = climate.capabilities
                State(
                    mode = climate.mode,
                    modes = caps.modes.ifEmpty { FALLBACK_MODES },
                    target = climate.targetTemperature,
                    shown = held ?: climate.targetTemperature,
                    current = climate.currentTemperature,
                    humidity = climate.humidity,
                    action = climate.action,
                    preset = climate.preset,
                    presets = caps.presets,
                    step = caps.step,
                    min = caps.range.start,
                    max = caps.range.endInclusive,
                    unit = climate.unit,
                )
            },
        )
    }

    fun setMode(mode: ClimateMode) {
        handle.command(ClimateCommands.setMode(mode))
    }

    /** Asks for [temperature], kept inside the device's range and rounded to hundredths, as the tile does; shown at once. */
    fun setTarget(temperature: Double) {
        val range = handle.entity?.capabilities?.range
        val low = range?.start ?: DEFAULT_MIN
        val high = range?.endInclusive ?: DEFAULT_MAX
        val clamped = Math.min(high, Math.max(low, Math.floor(temperature * 100 + 0.5) / 100))
        held = clamped
        refresh()
        handle.command(ClimateCommands.setTargetTemperature(clamped))
    }

    /** Moves the target by [steps] of the device's step (negative: down), from what is shown, so quick presses add up. */
    fun nudge(steps: Int) {
        val shown = state.value.shown ?: return
        setTarget(shown + steps * state.value.step)
    }

    fun setPreset(preset: String) {
        handle.command(ClimateCommands.setPreset(preset))
    }

    companion object {
        /** A device that reports no modes can be switched off and heated. */
        val FALLBACK_MODES: List<ClimateMode> = listOf(ClimateMode.Off, ClimateMode.Heat)

        private const val DEFAULT_MIN = 5.0
        private const val DEFAULT_MAX = 35.0

        private fun blank() = State(ClimateMode.Off, FALLBACK_MODES, null, null, null, null, null, null, emptyList(), 0.5, DEFAULT_MIN, DEFAULT_MAX, "°C")

        /** "heatCool" or "fan_only" as words: "Heatcool", "Fan only". */
        fun title(text: String): String = text.replace('_', ' ').let { it.substring(0, 1).uppercase() + it.substring(1) }

        /** A temperature as the web writes it: no trailing zero ("16.4", "17"). */
        fun number(value: Double): String = if (value == Math.rint(value)) value.toLong().toString() else value.toString()
    }
}

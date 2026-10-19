package dev.hashsome.headless

import dev.hashsome.client.LightColor
import dev.hashsome.client.LightCommands
import dev.hashsome.client.LightEntity
import dev.hashsome.client.Store
import kotlin.math.abs
import kotlin.math.roundToInt

/** A color a light can be set to in one tap: a white temperature, or a hue and saturation. */
data class LightPreset(val label: String?, val kelvin: Int? = null, val hue: Double? = null, val saturation: Double? = null)

/** The state and commands of a light tile (mirrors the web `light-color.ts` and `LightTile`). */
class LightControl(val handle: EntityHandle<LightEntity>) {
    /** 0..100: the level to show, which is the drag position while a finger is down and the reported brightness otherwise. */
    val shown: Store<Int> = Store(0)

    val on: Store<Boolean> = Store(false)

    // The level a finger has set and the light has not reported yet. Kept after the finger lifts, so the bar does not
    // snap back to the old level before the round trip is done, and dropped once the light reports that level.
    private var drag: Int? = null

    init {
        handle.state.listenNow { refresh() }
    }

    /** The presets this light can apply, given its capabilities. */
    val presets: List<LightPreset>
        get() {
            val caps = handle.entity?.capabilities ?: return emptyList()
            return DEFAULT_PRESETS.mapNotNull { (label, kelvin) ->
                when {
                    caps.colorTemperature -> LightPreset(label, kelvin = kelvin)
                    caps.color -> {
                        val (hue, saturation) = LightColors.rgbToHs(LightColors.kelvinToRgb(kelvin))
                        LightPreset(label, hue = hue.toDouble(), saturation = saturation.toDouble())
                    }

                    else -> null
                }
            }
        }

    /** Whether [preset] is the color the light is showing now. */
    fun isActive(preset: LightPreset): Boolean {
        val color = handle.entity?.color
        return when {
            preset.kelvin != null -> color is LightColor.Temperature && color.kelvin == preset.kelvin
            preset.hue != null && preset.saturation != null ->
                color is LightColor.Hs && color.hue.roundToInt() == preset.hue.roundToInt() &&
                    color.saturation.roundToInt() == preset.saturation.roundToInt()

            else -> false
        }
    }

    fun toggle() {
        handle.command(LightCommands.toggle())
    }

    /** A finger is dragging the level: [level] (0..100) is shown at once and nothing is sent. */
    fun drag(level: Int) {
        drag = level.coerceIn(0, 100)
        refresh()
    }

    /** The finger lifted at [level]: sends the brightness (0 turns the light off). */
    fun commit(level: Int) {
        val clamped = level.coerceIn(0, 100)
        drag = clamped
        refresh()
        handle.command(LightCommands.setBrightness(clamped / 100.0))
    }

    fun setKelvin(kelvin: Int) {
        handle.command(LightCommands.setColorTemperature(kelvin))
    }

    fun setHs(hue: Double, saturation: Double) {
        handle.command(LightCommands.setColor(hue, saturation))
    }

    private fun refresh() {
        val light = handle.entity
        val isOn = light?.on == true
        // What the light is at, on a 0..100 scale: its brightness while on (all the way, if it cannot dim), nothing while off.
        val reported = if (isOn) (light?.brightness ?: 1.0) * 100 else 0.0
        val held = drag
        if (held != null && abs(reported - held) < 0.5) drag = null
        on.set(isOn)
        shown.set(drag ?: reported.let { Math.floor(it + 0.5).toInt() })
    }

    private companion object {
        /** The swatches of a light tile (`DEFAULT_COLOR_PRESETS`). */
        val DEFAULT_PRESETS = listOf("Candle" to 2200, "Warm white" to 2700, "Neutral white" to 4000, "Daylight" to 6500)
    }
}

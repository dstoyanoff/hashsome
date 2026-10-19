package dev.hashsome.headless

import dev.hashsome.client.ClimateCommands
import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.Store

/** What a climate tile shows and does. M1 only draws its disabled state; the commands are here for M2. */
class ClimateControl(val handle: EntityHandle<ClimateEntity>) {
    val mode: Store<ClimateMode> = Store(ClimateMode.Off)
    val target: Store<Double?> = Store(null)
    val current: Store<Double?> = Store(null)

    init {
        handle.state.listenNow {
            val climate = handle.entity
            mode.set(climate?.mode ?: ClimateMode.Off)
            target.set(climate?.targetTemperature)
            current.set(climate?.currentTemperature)
        }
    }

    fun setMode(mode: ClimateMode) {
        handle.command(ClimateCommands.setMode(mode))
    }

    /** Asks for [temperature], kept inside the device's range and rounded to hundredths, as the tile does. */
    fun setTarget(temperature: Double) {
        val range = handle.entity?.capabilities?.range
        val low = range?.start ?: DEFAULT_MIN
        val high = range?.endInclusive ?: DEFAULT_MAX
        val clamped = Math.min(high, Math.max(low, Math.floor(temperature * 100 + 0.5) / 100))
        handle.command(ClimateCommands.setTargetTemperature(clamped))
    }

    fun setPreset(preset: String) {
        handle.command(ClimateCommands.setPreset(preset))
    }

    private companion object {
        const val DEFAULT_MIN = 5.0
        const val DEFAULT_MAX = 35.0
    }
}

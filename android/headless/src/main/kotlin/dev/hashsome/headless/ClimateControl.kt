package dev.hashsome.headless

import dev.hashsome.client.ClimateEntity
import dev.hashsome.client.ClimateMode
import dev.hashsome.client.Store

/** What a climate tile shows and does. M1 only draws its disabled state; the commands are here for M2. */
class ClimateControl(val handle: EntityHandle<ClimateEntity>) {
    val mode: Store<ClimateMode> = Store(ClimateMode.Off)
    val target: Store<Double?> = Store(null)
    val current: Store<Double?> = Store(null)

    fun setMode(mode: ClimateMode): Unit = TODO("WP2")
    fun setTarget(temperature: Double): Unit = TODO("WP2")
    fun setPreset(preset: String): Unit = TODO("WP2")
}

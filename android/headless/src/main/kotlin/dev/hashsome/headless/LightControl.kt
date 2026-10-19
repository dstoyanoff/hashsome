package dev.hashsome.headless

import dev.hashsome.client.LightEntity
import dev.hashsome.client.Store

/** A color a light can be set to in one tap: a white temperature, or a hue and saturation. */
data class LightPreset(val label: String?, val kelvin: Int? = null, val hue: Double? = null, val saturation: Double? = null)

/** The state and commands of a light tile (mirrors the web `light-color.ts` and `LightTile`). */
class LightControl(val handle: EntityHandle<LightEntity>) {
    /** 0..100: the level to show, which is the drag position while a finger is down and the reported brightness otherwise. */
    val shown: Store<Int> = Store(0)

    val on: Store<Boolean> = Store(false)

    /** The presets this light can apply, given its capabilities. */
    val presets: List<LightPreset> get() = TODO("WP2")

    fun toggle(): Unit = TODO("WP2")

    /** A finger is dragging the level: [level] (0..100) is shown at once and nothing is sent. */
    fun drag(level: Int): Unit = TODO("WP2")

    /** The finger lifted at [level]: sends the brightness (0 turns the light off). */
    fun commit(level: Int): Unit = TODO("WP2")

    fun setKelvin(kelvin: Int): Unit = TODO("WP2")
    fun setHs(hue: Double, saturation: Double): Unit = TODO("WP2")
}

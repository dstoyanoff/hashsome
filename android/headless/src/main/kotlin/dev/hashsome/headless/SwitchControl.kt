package dev.hashsome.headless

import dev.hashsome.client.SensorEntity
import dev.hashsome.client.Store
import dev.hashsome.client.SwitchEntity

/** A switch, and optionally the power sensor that says what it draws (mirrors `home/shared/switch-tile.tsx`). */
class SwitchControl(val handle: EntityHandle<SwitchEntity>, val power: EntityHandle<SensorEntity>? = null) {
    val on: Store<Boolean> = Store(false)

    /** Watts drawn, when a power sensor was given and it reports a number. */
    val watts: Store<Double?> = Store(null)

    fun toggle(): Unit = TODO("WP2")
}

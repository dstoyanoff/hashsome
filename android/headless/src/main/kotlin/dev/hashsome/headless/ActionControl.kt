package dev.hashsome.headless

import dev.hashsome.client.ActionEntity
import dev.hashsome.client.Store

/** A one-shot action (scene, script, button): `trigger` it and see that it is pending until the runtime answers. */
class ActionControl(val handle: EntityHandle<ActionEntity>) {
    /** True from [trigger] until the command has an outcome. */
    val pending: Store<Boolean> = Store(false)

    /** The error of the last trigger, cleared by the next one. */
    val error: Store<String?> = Store(null)

    fun trigger(): Unit = TODO("WP2")
}

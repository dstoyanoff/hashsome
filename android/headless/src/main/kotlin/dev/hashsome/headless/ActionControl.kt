package dev.hashsome.headless

import dev.hashsome.client.ActionCommands
import dev.hashsome.client.ActionEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.Store

/** A one-shot action (scene, script, button): `trigger` it and see that it is pending until the runtime answers. */
class ActionControl(val handle: EntityHandle<ActionEntity>) {
    /** True from [trigger] until the command has an outcome. */
    val pending: Store<Boolean> = Store(false)

    /** The error of the last trigger, cleared by the next one. */
    val error: Store<String?> = Store(null)

    /** Runs the action. A trigger while the last one is pending is ignored (the web button cannot be pressed then). */
    fun trigger() {
        if (pending.value) return
        error.set(null)
        if (handle.entity == null) {
            error.set(NOTHING_TO_TRIGGER)
            return
        }

        pending.set(true)
        handle.command(ActionCommands.trigger()) { outcome ->
            pending.set(false)
            if (outcome is Outcome.Failed) error.set(outcome.error)
        }
    }

    companion object {
        /**
         * `error` stays until the next trigger; the web button clears its feedback after 1200 ms and has a "done" state, which
         * this control does not.
         */
        /** What `error` says when there is no entity to run. */
        const val NOTHING_TO_TRIGGER = "Nothing to trigger"
    }
}

package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.EntityRef
import dev.hashsome.client.HashsomeClient
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store

/**
 * What a screen holds on to while it is shown: its watches on entities, its listeners, its timers.
 * Leaving the screen disposes the scope and all of it lets go, and nothing a disposed scope had
 * registered is called afterwards.
 */
class Scope(val client: HashsomeClient, val scheduler: Scheduler) {
    /** Calls [fn] with the entity's state, at once when known and on every change, until disposed. */
    fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel = TODO("WP2")

    /** Calls [fn] with [store]'s current value and each later one, until disposed. */
    fun <T> bind(store: Store<T>, fn: (T) -> Unit): Cancel = TODO("WP2")

    fun after(ms: Long, fn: () -> Unit): Cancel = TODO("WP2")
    fun every(ms: Long, fn: () -> Unit): Cancel = TODO("WP2")

    /** A scope that is disposed with this one, or earlier on its own (a popover's, a drawer's). */
    fun child(): Scope = TODO("WP2")

    /** Cancels everything registered through this scope and its children. Calling it again does nothing. */
    fun dispose(): Unit = TODO("WP2")
}

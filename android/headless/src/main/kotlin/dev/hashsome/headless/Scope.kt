package dev.hashsome.headless

import dev.hashsome.client.Cancel
import dev.hashsome.client.Client
import dev.hashsome.client.Command
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Outcome
import dev.hashsome.client.Query
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import org.json.JSONObject

/**
 * What a screen holds on to while it is shown: its watches on entities, its listeners, its timers.
 * Leaving the screen disposes the scope and all of it lets go, and nothing a disposed scope had
 * registered is called afterwards.
 */
class Scope(val client: Client, val scheduler: Scheduler) {
    // Insertion order, so that dispose lets go in the order things were registered.
    private val cancels = LinkedHashSet<Cancel>()
    private val children = ArrayList<Scope>()
    private var parent: Scope? = null

    /** True once [dispose] has run: nothing registered here is called any more, and nothing new is registered. */
    var disposed: Boolean = false
        private set

    /** Calls [fn] with the entity's state, at once when known and on every change, until disposed. */
    fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel {
        if (disposed) return {}
        return register(client.watch(ref) { if (!disposed) fn(it) })
    }

    /** Calls [fn] with [store]'s current value and each later one, until disposed. */
    fun <T> bind(store: Store<T>, fn: (T) -> Unit): Cancel {
        if (disposed) return {}
        val cancel = register(store.listen { if (!disposed) fn(it) })
        fn(store.value)
        return cancel
    }

    fun after(ms: Long, fn: () -> Unit): Cancel {
        if (disposed) return {}
        var cancel: Cancel = {}
        cancel = register(
            scheduler.after(ms) {
                cancels.remove(cancel)
                if (!disposed) fn()
            },
        )

        return cancel
    }

    fun every(ms: Long, fn: () -> Unit): Cancel {
        if (disposed) return {}
        return register(scheduler.every(ms) { if (!disposed) fn() })
    }

    /** A scope that is disposed with this one, or earlier on its own (a popover's, a drawer's). */
    fun child(): Scope {
        val child = Scope(client, scheduler)
        if (disposed) {
            child.disposed = true
            return child
        }

        child.parent = this
        children += child
        return child
    }

    /** Cancels everything registered through this scope and its children. Calling it again does nothing. */
    fun dispose() {
        if (disposed) return
        disposed = true
        parent?.children?.remove(this)
        parent = null
        for (child in children.toList()) child.dispose()
        children.clear()
        for (cancel in cancels.toList()) cancel()
        cancels.clear()
    }

    /** Holds [cancel] until this scope is disposed (or the returned cancel is called, which also calls [cancel]). */
    internal fun register(cancel: Cancel): Cancel {
        if (disposed) {
            cancel()
            return {}
        }

        var wrapped: Cancel = {}
        wrapped = {
            if (cancels.remove(wrapped)) cancel()
        }

        cancels += wrapped
        return wrapped
    }

    /** A command whose outcome is dropped if this scope is disposed before it arrives. */
    internal fun command(ref: EntityRef, name: String, args: JSONObject?, done: ((Outcome) -> Unit)?): Cancel {
        if (disposed) return {}
        if (done == null) return register(client.command(ref, name, args, null))
        return register(client.command(ref, name, args) { if (!disposed) done(it) })
    }

    internal fun command(ref: EntityRef, command: Command, done: ((Outcome) -> Unit)?): Cancel = command(ref, command.name, command.args, done)

    /** A query whose answer is dropped if this scope is disposed before it arrives. */
    internal fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel {
        if (disposed) return {}
        return register(client.query(ref, query) { if (!disposed) done(it) })
    }

    /**
     * Calls [fn] each time the link to the runtime opens after not being open (the reconnect a read that failed while
     * it was down is asked again on, as `useReconnects` does on the web). Opening for the first time counts, when the
     * link was still connecting.
     */
    internal fun onReconnect(fn: () -> Unit): Cancel {
        if (disposed) return {}
        var was = client.link.value
        return register(
            client.link.listen { now ->
                val reopened = now == LinkStatus.Open && was != LinkStatus.Open
                was = now
                if (reopened && !disposed) fn()
            },
        )
    }
}

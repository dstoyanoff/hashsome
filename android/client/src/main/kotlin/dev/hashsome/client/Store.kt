package dev.hashsome.client

/** Undoes a registration (a listener, a timer, a watch). Calling it twice is harmless. */
typealias Cancel = () -> Unit

/**
 * One observable value. Everything that changes on screen is a Store, and a view listens to it
 * for as long as it is attached.
 *
 * Main thread only: nothing here locks, because the whole app updates its UI from one thread and
 * a lock would only hide a callback that arrived from the wrong one. Setting a value equal to the
 * current one (by `==`) does not notify, so a repeated server message costs a view nothing.
 *
 * A listener that throws propagates the exception to whoever called [set]: the value is already
 * the new one, the listeners after the thrower are not called for it, and the store stays usable.
 */
class Store<T>(initial: T) {
    private class Entry<T>(val fn: (T) -> Unit) {
        var live = true
    }

    private val entries = ArrayList<Entry<T>>()

    // Counts the sets, so a notification can tell that a listener set another value (even the same one again) meanwhile.
    private var version = 0

    var value: T = initial
        private set

    fun set(next: T) {
        if (next == value) return
        value = next
        val mine = ++version
        // Walk a copy, so a listener that cancels itself or another (or adds one) cannot skip or repeat a call.
        // A listener that sets a newer value has already told everyone about it, so this one is not delivered late.
        for (entry in entries.toTypedArray()) {
            if (entry.live && version == mine) entry.fn(next)
        }
    }

    /** Calls [fn] with each later value. */
    fun listen(fn: (T) -> Unit): Cancel {
        val entry = Entry(fn)
        entries += entry
        return {
            entry.live = false
            entries.remove(entry)
        }
    }

    /** Like [listen], and calls [fn] with the current value first. */
    fun listenNow(fn: (T) -> Unit): Cancel {
        val cancel = listen(fn)
        fn(value)
        return cancel
    }
}

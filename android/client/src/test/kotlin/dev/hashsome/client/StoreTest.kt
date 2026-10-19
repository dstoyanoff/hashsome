package dev.hashsome.client

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTest {
    @Test
    fun `listen hears later values only`() {
        val store = Store(1)
        val seen = ArrayList<Int>()
        store.listen { seen += it }
        assertEquals(emptyList<Int>(), seen)
        store.set(2)
        assertEquals(listOf(2), seen)
    }

    @Test
    fun `listenNow hears the current value first`() {
        val store = Store("a")
        val seen = ArrayList<String>()
        store.listenNow { seen += it }
        store.set("b")
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun `an equal value does not notify`() {
        val store = Store(listOf(1, 2))
        var calls = 0
        store.listen { calls++ }
        store.set(listOf(1, 2))
        assertEquals(0, calls)
        store.set(listOf(1))
        assertEquals(1, calls)
    }

    @Test
    fun `cancel stops notifications and is idempotent`() {
        val store = Store(0)
        var calls = 0
        val cancel = store.listen { calls++ }
        store.set(1)
        cancel()
        cancel()
        store.set(2)
        assertEquals(1, calls)
        assertEquals(2, store.value)
    }

    @Test
    fun `a listener may cancel another that is still to be called`() {
        val store = Store(0)
        val calls = ArrayList<String>()
        var cancelSecond: Cancel = {}
        store.listen { calls += "first"; cancelSecond() }
        cancelSecond = store.listen { calls += "second" }
        store.set(1)
        assertEquals(listOf("first"), calls)
    }

    @Test
    fun `a listener added during a notification waits for the next value`() {
        val store = Store(0)
        val calls = ArrayList<String>()
        store.listen {
            if (it == 1) store.listen { v -> calls += "late$v" }
        }
        store.set(1)
        assertEquals(emptyList<String>(), calls)
        store.set(2)
        assertEquals(listOf("late2"), calls)
    }

    @Test
    fun `a set from inside a listener is not followed by the stale value`() {
        val store = Store(0)
        val seen = ArrayList<Int>()
        store.listen { if (it == 1) store.set(2) }
        store.listen { seen += it }
        store.set(1)
        // The nested set reaches everyone first; the second listener never sees 1 after 2.
        assertEquals(listOf(2), seen)
        assertEquals(2, store.value)
    }
}

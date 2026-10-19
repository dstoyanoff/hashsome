package dev.hashsome.headless

import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopeTest {
    private val rig = Rig()

    @Test
    fun `watch delivers the entity at once and on every change`() {
        rig.client.set(ref("plug"), switch(on = false))
        val seen = ArrayList<Snapshot>()
        rig.scope.watch(ref("plug")) { seen += it }
        rig.settle()
        rig.client.set(ref("plug"), switch(on = true))
        rig.settle()
        assertEquals(listOf<Snapshot>(Snapshot.Present(switch(on = false)), Snapshot.Present(switch(on = true))), seen)
    }

    @Test
    fun `bind calls with the current value and each later one`() {
        val store = Store(1)
        val seen = ArrayList<Int>()
        rig.scope.bind(store) { seen += it }
        store.set(2)
        store.set(2)
        store.set(3)
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `nothing fires after dispose`() {
        val store = Store(0)
        val fired = ArrayList<String>()
        rig.scope.watch(ref("plug")) { fired += "watch" }
        rig.scope.bind(store) { fired += "bind$it" }
        rig.scope.after(100) { fired += "after" }
        rig.scope.every(50) { fired += "every" }
        rig.settle()
        fired.clear()

        rig.scope.dispose()
        store.set(1)
        rig.client.set(ref("plug"), switch())
        rig.scheduler.advance(10_000)
        assertEquals(emptyList<String>(), fired)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `a delivery already queued when the scope is disposed is dropped`() {
        val fired = ArrayList<String>()
        rig.client.set(ref("plug"), switch())
        rig.scope.watch(ref("plug")) { fired += "watch" }
        rig.scope.dispose()
        rig.settle()
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun `after fires once at its time`() {
        var n = 0
        rig.scope.after(100) { n++ }
        rig.scheduler.advance(99)
        assertEquals(0, n)
        rig.scheduler.advance(1000)
        assertEquals(1, n)
    }

    @Test
    fun `a cancel from a registration stops only that one`() {
        val fired = ArrayList<String>()
        val a = rig.scope.after(10) { fired += "a" }
        rig.scope.after(10) { fired += "b" }
        a()
        rig.scheduler.advance(10)
        assertEquals(listOf("b"), fired)
    }

    @Test
    fun `a child is disposed with its parent`() {
        val child = rig.scope.child()
        var n = 0
        child.every(10) { n++ }
        rig.scheduler.advance(30)
        assertEquals(3, n)
        rig.scope.dispose()
        assertTrue(child.disposed)
        rig.scheduler.advance(100)
        assertEquals(3, n)
    }

    @Test
    fun `a child can go early and leaves its parent alone`() {
        val child = rig.scope.child()
        var parent = 0
        var kid = 0
        rig.scope.every(10) { parent++ }
        child.every(10) { kid++ }
        rig.scheduler.advance(20)
        child.dispose()
        rig.scheduler.advance(20)
        assertEquals(4, parent)
        assertEquals(2, kid)
        assertFalse(rig.scope.disposed)
    }

    @Test
    fun `a child of a disposed scope is born disposed`() {
        rig.scope.dispose()
        val child = rig.scope.child()
        var n = 0
        child.after(1) { n++ }
        rig.scheduler.advance(10)
        assertTrue(child.disposed)
        assertEquals(0, n)
    }

    @Test
    fun `disposing twice does nothing`() {
        var cancelled = 0
        rig.scope.register { cancelled++ }
        rig.scope.dispose()
        rig.scope.dispose()
        assertEquals(1, cancelled)
    }

    @Test
    fun `registering on a disposed scope cancels at once`() {
        rig.scope.dispose()
        var cancelled = 0
        rig.scope.register { cancelled++ }
        assertEquals(1, cancelled)
    }

    @Test
    fun `a command or query answered after dispose is not reported`() {
        rig.client.set(ref("plug"), switch())
        rig.client.answerValue(ref("room"), "browse", dev.hashsome.client.BrowseResult(items = emptyList()))
        var heard = 0
        rig.scope.command(ref("plug"), "toggle", null) { heard++ }
        rig.scope.query(ref("room"), dev.hashsome.client.Query.Browse()) { heard++ }
        rig.scope.dispose()
        rig.settle()
        assertEquals(0, heard)
    }

    @Test
    fun `a reconnect is the link opening after it was not open`() {
        var n = 0
        rig.scope.onReconnect { n++ }
        rig.client.link.set(LinkStatus.Open)
        assertEquals(0, n)
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Connecting)
        rig.client.link.set(LinkStatus.Open)
        assertEquals(1, n)
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        assertEquals(2, n)
    }

    @Test
    fun `a link that was still connecting counts when it first opens`() {
        rig.client.link.set(LinkStatus.Connecting)
        var n = 0
        rig.scope.onReconnect { n++ }
        rig.client.link.set(LinkStatus.Open)
        assertEquals(1, n)
    }
}

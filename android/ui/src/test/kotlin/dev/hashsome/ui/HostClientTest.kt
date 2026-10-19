package dev.hashsome.ui

import dev.hashsome.client.Availability
import dev.hashsome.client.Client
import dev.hashsome.client.EntityRef
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.SensorEntity
import dev.hashsome.client.ServerAddress
import dev.hashsome.client.Snapshot
import dev.hashsome.headless.Scope
import dev.hashsome.headless.ThemeController
import dev.hashsome.headless.ThemeSchedule
import dev.hashsome.testing.FakeClient
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// `Hashsome.client` is one object for the process: reconnecting keeps what is watching it, and app-lifetime scopes survive.
class HostClientTest {
    private val scheduler = TestScheduler()
    private val none = FakeClient(scheduler).also { it.link.set(LinkStatus.Closed) }
    private val made = ArrayList<Pair<ServerAddress, FakeClient>>()
    private val sun = EntityRef("ha:sun.sun")
    private val a = ServerAddress("http://a:1")
    private val b = ServerAddress("http://b:2")

    private fun host(initial: ServerAddress? = null) = HostClient(none, initial) { address ->
        FakeClient(scheduler).also { made += address to it }
    }

    private fun daylight(on: Boolean) =
        SensorEntity(sun, "Sun", Availability.Ready, if (on) "on" else "off", measurement = "daylight")

    @Test
    fun `a provisioned server is reconnected in place, not replaced`() {
        val host = host(a)
        val client = made.single().second
        val seen = ArrayList<Snapshot>()
        host.watch(sun) { seen += it }
        host.reconnect(b)
        assertEquals(1, made.size)
        assertEquals(listOf<ServerAddress?>(b), client.reconnects)
        host.point(null)
        host.point(a)
        assertEquals(2, made.size)
    }

    @Test
    fun `reloading with the same address reconnects the same client`() {
        val host = host(a)
        host.point(a)
        assertEquals(listOf<ServerAddress?>(a), made.single().second.reconnects)
    }

    @Test
    fun `an app scope keeps its watches across a reconnect`() {
        val host = host(a)
        val client = made.single().second
        val app = Scope(host, scheduler)
        val seen = ArrayList<Boolean>()
        app.watch(sun) { s -> seen += (s as? Snapshot.Present)?.entity?.let { (it as SensorEntity).value == "on" } ?: false }
        client.set(sun, daylight(true))
        scheduler.runCurrent()
        host.point(a)
        client.set(sun, daylight(false))
        scheduler.runCurrent()
        // The first answer is the entity not being there yet.
        assertEquals(listOf(false, true, false), seen)
        assertFalse(app.disposed)
    }

    @Test
    fun `the first server takes over the watches of the stand-in`() {
        val host = host()
        val app = Scope(host, scheduler)
        val seen = ArrayList<Snapshot>()
        app.watch(sun) { seen += it }
        host.point(a)
        val real = made.single().second
        real.set(sun, daylight(true))
        scheduler.runCurrent()
        assertTrue(seen.last() is Snapshot.Present)
        // The stand-in is let go of and the link follows the real client.
        real.link.set(LinkStatus.Connecting)
        assertEquals(LinkStatus.Connecting, host.link.value)
    }

    @Test
    fun `a cancelled watch is not carried over`() {
        val host = host()
        val seen = ArrayList<Snapshot>()
        val cancel = host.watch(sun) { seen += it }
        cancel()
        val before = seen.size
        host.point(a)
        made.single().second.set(sun, daylight(true))
        scheduler.runCurrent()
        assertEquals(before, seen.size)
    }

    @Test
    fun `the theme schedule keeps following the sun through a reconnect`() {
        val host = host(a)
        val real = made.single().second
        val theme = ThemeController(ThemeSchedule.Sun(sun), Scope(host, scheduler))
        real.set(sun, daylight(false))
        scheduler.runCurrent()
        assertTrue(theme.dark.value)
        host.point(b)
        real.set(sun, daylight(true))
        scheduler.runCurrent()
        assertFalse(theme.dark.value)
        // And across the stand-in being replaced.
        host.point(null)
        host.point(a)
        val second = made.last().second
        second.set(sun, daylight(false))
        scheduler.runCurrent()
        assertTrue(theme.dark.value)
    }

    @Test
    fun `a host client is not connected until connecting is asked for, and reconnecting asks for it`() {
        val lazy = host(a)
        val first = made.last().second
        first.link.set(LinkStatus.Closed)
        // Nothing connects it by itself.
        assertEquals(LinkStatus.Closed, lazy.link.value)
        lazy.connect()
        assertEquals(LinkStatus.Open, lazy.link.value)

        // A stand-in being replaced is connected at once when reconnect() asks for it.
        val fresh = host()
        fresh.reconnect(null)
        fresh.reconnect(b)
        assertEquals(LinkStatus.Open, fresh.link.value)
        assertEquals(b, made.last().first)
    }

    @Test
    fun `reconnecting with no address reopens the address it has`() {
        val host = host(a)
        host.reconnect(null)
        assertEquals(listOf<ServerAddress?>(null), made.single().second.reconnects)
    }
}

package dev.hashsome.testing

import dev.hashsome.client.BrowseResult
import dev.hashsome.client.LightCommands
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.Outcome
import dev.hashsome.client.Query
import dev.hashsome.client.Snapshot
import dev.hashsome.headless.item
import dev.hashsome.headless.json
import dev.hashsome.headless.light
import dev.hashsome.headless.player
import dev.hashsome.headless.ref
import dev.hashsome.headless.switch
import dev.hashsome.client.LightEntity
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.SwitchEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeClientTest {
    private val scheduler = TestScheduler()
    private val client = FakeClient(scheduler)

    private fun watched(name: String): Pair<MutableList<Snapshot>, () -> Unit> {
        val seen = ArrayList<Snapshot>()
        return seen to client.watch(ref(name)) { seen += it }
    }

    @Test
    fun `an entity never set is loading, through the scheduler`() {
        val (seen, _) = watched("lamp")
        assertEquals(emptyList<Snapshot>(), seen)
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Loading), seen)
    }

    @Test
    fun `a known entity is delivered at once, and a change after it`() {
        client.set(ref("lamp"), light(on = false))
        val (seen, _) = watched("lamp")
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Present(light(on = false))), seen)
        client.set(ref("lamp"), light(on = true))
        assertEquals(1, seen.size)
        scheduler.runCurrent()
        assertEquals(Snapshot.Present(light(on = true)), seen.last())
    }

    @Test
    fun `a removed entity is missing, and one removed before the watch is missing at once`() {
        client.set(ref("lamp"), light())
        val (seen, _) = watched("lamp")
        scheduler.runCurrent()
        client.remove(ref("lamp"))
        scheduler.runCurrent()
        assertEquals(Snapshot.Missing, seen.last())
        val (later, _) = watched("lamp")
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Missing), later)
    }

    @Test
    fun `nothing arrives after a watch is cancelled`() {
        val (seen, cancel) = watched("lamp")
        scheduler.runCurrent()
        cancel()
        cancel()
        client.set(ref("lamp"), light())
        scheduler.runCurrent()
        assertEquals(1, seen.size)
    }

    @Test
    fun `a watch cancelled before its first delivery gets nothing`() {
        val (seen, cancel) = watched("lamp")
        cancel()
        scheduler.runCurrent()
        assertEquals(emptyList<Snapshot>(), seen)
    }

    @Test
    fun `commands are recorded in order with their args`() {
        client.command(ref("lamp"), "turnOn")
        client.command(ref("lamp"), "setBrightness", json("brightness" to 0.3))
        assertEquals(listOf("turnOn", "setBrightness"), client.commands.map { it.second })
        assertNull(client.commands[0].third)
        assertEquals(0.3, client.commands[1].third!!.getDouble("brightness"), 0.0)
        client.command(ref("lamp"), LightCommands.toggle())
        assertEquals("toggle", client.commands.last().second)
    }

    @Test
    fun `the default hook applies a light and switch change, which watchers then see`() {
        client.set(ref("lamp"), light(on = false, brightness = 0.5))
        client.set(ref("plug"), switch(on = false))
        val seen = ArrayList<Snapshot>()
        client.watch(ref("lamp")) { seen += it }
        scheduler.runCurrent()
        client.command(ref("lamp"), "toggle")
        client.command(ref("plug"), "toggle")
        scheduler.runCurrent()
        assertTrue((client.entity(ref("lamp")) as LightEntity).on)
        assertTrue((client.entity(ref("plug")) as SwitchEntity).on)
        assertEquals(Snapshot.Present(light(on = true, brightness = 0.5)), seen.last())

        client.command(ref("lamp"), "setBrightness", json("brightness" to 0.0))
        scheduler.runCurrent()
        assertEquals(false, (client.entity(ref("lamp")) as LightEntity).on)
        client.command(ref("lamp"), "setBrightness", json("brightness" to 0.8))
        scheduler.runCurrent()
        assertEquals(0.8, (client.entity(ref("lamp")) as LightEntity).brightness!!, 0.0)
        assertTrue((client.entity(ref("lamp")) as LightEntity).on)
    }

    @Test
    fun `the default hook plays, pauses, sets volume and stamps a seek with the wall clock`() {
        scheduler.wall = 1_780_315_200_000 // 2026-06-01T12:00:00Z
        client.set(ref("room"), player())
        client.command(ref("room"), MediaPlayerCommands.play())
        client.command(ref("room"), MediaPlayerCommands.setVolume(0.7))
        client.command(ref("room"), MediaPlayerCommands.seek(42.0))
        scheduler.runCurrent()
        val p = client.entity(ref("room")) as MediaPlayerEntity
        assertEquals(dev.hashsome.client.PlaybackState.Playing, p.playback)
        assertEquals(0.7, p.volume!!, 0.0)
        assertEquals(42.0, p.position!!, 0.0)
        assertEquals("2026-06-01T12:00:00.000Z", p.positionUpdatedAt)
    }

    @Test
    fun `a custom hook replaces the default, and null leaves state alone`() {
        client.set(ref("plug"), switch(on = false))
        val hooked = ArrayList<String>()
        client.onCommand = { r, name, _ -> hooked += "$r $name" }
        client.command(ref("plug"), "toggle")
        scheduler.runCurrent()
        assertEquals(listOf("ha:plug toggle"), hooked)
        assertEquals(false, (client.entity(ref("plug")) as SwitchEntity).on)
        client.onCommand = null
        client.command(ref("plug"), "toggle")
        scheduler.runCurrent()
        assertEquals(false, (client.entity(ref("plug")) as SwitchEntity).on)
    }

    @Test
    fun `the outcome is ok, or the failure queued, and a failure changes nothing`() {
        client.set(ref("plug"), switch(on = false))
        val outcomes = ArrayList<Outcome>()
        client.command(ref("plug"), "toggle") { outcomes += it }
        scheduler.runCurrent()
        assertEquals(listOf<Outcome>(Outcome.Ok), outcomes)
        client.failNext("nope")
        client.command(ref("plug"), "toggle") { outcomes += it }
        client.command(ref("plug"), "toggle") { outcomes += it }
        scheduler.runCurrent()
        assertEquals(listOf(Outcome.Ok, Outcome.Failed("nope"), Outcome.Ok), outcomes)
        // Toggled by the first and third only.
        assertEquals(false, (client.entity(ref("plug")) as SwitchEntity).on)
    }

    @Test
    fun `a cancelled command still takes effect but does not report`() {
        client.set(ref("plug"), switch(on = false))
        var heard = false
        val cancel = client.command(ref("plug"), "toggle") { heard = true }
        cancel()
        scheduler.runCurrent()
        assertEquals(false, heard)
        assertTrue((client.entity(ref("plug")) as SwitchEntity).on)
    }

    @Test
    fun `a registered answer is given, parsed answers need no json, and asks are recorded`() {
        val result = BrowseResult(items = listOf(item("a")))
        client.answerValue(ref("room"), "browse", result)
        var got: Result<BrowseResult>? = null
        client.query(ref("room"), Query.Browse()) { got = it }
        assertNull(got)
        scheduler.runCurrent()
        assertEquals(result, got!!.getOrNull())
        assertEquals(listOf(ref("room") to "browse"), client.queries)
    }

    @Test
    fun `answerWith sees the query that was asked`() {
        client.answerWith(ref("room"), "browse") { q ->
            val browse = q as Query.Browse
            BrowseResult(items = listOf(item(browse.path ?: "top")))
        }

        val titles = ArrayList<String>()
        client.query(ref("room"), Query.Browse(path = "albums")) { titles += it.getOrThrow().items.single().id }
        client.query(ref("room"), Query.Browse()) { titles += it.getOrThrow().items.single().id }
        scheduler.runCurrent()
        assertEquals(listOf("albums", "top"), titles)
    }

    @Test
    fun `a query with no answer, or while failQuery is set, fails`() {
        var got: Result<BrowseResult>? = null
        client.query(ref("room"), Query.Browse()) { got = it }
        scheduler.runCurrent()
        assertTrue(got!!.isFailure)

        client.answerValue(ref("room"), "browse", BrowseResult(items = emptyList()))
        client.failQuery("Not connected")
        client.query(ref("room"), Query.Browse()) { got = it }
        scheduler.runCurrent()
        assertEquals("Not connected", got!!.exceptionOrNull()!!.message)
        client.failQuery(null)
        client.query(ref("room"), Query.Browse()) { got = it }
        scheduler.runCurrent()
        assertTrue(got!!.isSuccess)
    }

    @Test
    fun `a query answers after its delay, and not once cancelled`() {
        client.answerValue(ref("room"), "browse", BrowseResult(items = emptyList()))
        client.queryDelayMs = 200
        var answered = 0
        client.query(ref("room"), Query.Browse()) { answered++ }
        val cancel = client.query(ref("room"), Query.Browse()) { answered++ }
        cancel()
        scheduler.advance(199)
        assertEquals(0, answered)
        scheduler.advance(1)
        assertEquals(1, answered)
    }

    @Test
    fun `link and integrations are plain stores, connect opens and close closes`() {
        assertEquals(LinkStatus.Open, client.link.value)
        client.close()
        assertEquals(LinkStatus.Closed, client.link.value)
        client.connect()
        assertEquals(LinkStatus.Open, client.link.value)
        client.integrations.set(mapOf("ha" to dev.hashsome.client.ConnectionStatus.Connected))
        assertEquals(1, client.integrations.value.size)
    }
}

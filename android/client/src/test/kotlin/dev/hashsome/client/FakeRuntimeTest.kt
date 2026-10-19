package dev.hashsome.client

import dev.hashsome.testing.EntityJson
import dev.hashsome.testing.FakeRuntime
import dev.hashsome.testing.Fixtures
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The test runtime itself: it has to behave like the real one for the headless and UI tests built on it to mean anything. */
class FakeRuntimeTest {
    private val runtime = FakeRuntime()
    private val client = runtime.client
    private val scheduler = runtime.scheduler
    private val light = EntityRef("ha:light.lamp")

    private fun lamp(on: Boolean = false, brightness: Double? = null) =
        LightEntity(light, "Lamp", Availability.Ready, on, brightness, capabilities = LightCapabilities(brightness = true, colorTemperature = false, color = false))

    private fun connect() {
        client.connect()
        scheduler.runCurrent()
    }

    @Test
    fun `connecting opens the link and tells the integrations' status`() {
        runtime.put(lamp())
        connect()
        assertEquals(LinkStatus.Open, client.link.value)
        assertEquals(mapOf("ha" to ConnectionStatus.Connected), client.integrations.value)
        runtime.setStatus("ha", ConnectionStatus.Error)
        scheduler.runCurrent()
        assertEquals(ConnectionStatus.Error, client.integrations.value["ha"])
    }

    @Test
    fun `a subscription is answered at once and followed by every change`() {
        runtime.put(lamp(on = true))
        connect()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        scheduler.runCurrent()
        assertEquals(Snapshot.Present(lamp(on = true)), seen.last())
        runtime.put(lamp(on = false))
        scheduler.runCurrent()
        assertEquals(Snapshot.Present(lamp(on = false)), seen.last())
        runtime.remove(light)
        scheduler.runCurrent()
        assertEquals(Snapshot.Missing, seen.last())
    }

    @Test
    fun `an entity that was never made is missing`() {
        connect()
        val seen = ArrayList<Snapshot>()
        client.watch(EntityRef("ha:nothing")) { seen += it }
        scheduler.runCurrent()
        assertEquals(Snapshot.Missing, seen.last())
    }

    @Test
    fun `commands are recorded, applied and answered`() {
        runtime.put(lamp())
        connect()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        var outcome: Outcome? = null
        client.command(light, LightCommands.setBrightness(0.4)) { outcome = it }
        scheduler.runCurrent()
        assertEquals(Outcome.Ok, outcome)
        assertEquals(listOf(Triple(light, "setBrightness", """{"brightness":0.4}""")), runtime.commands)
        assertEquals(Snapshot.Present(lamp(on = true, brightness = 0.4)), seen.last())
        client.command(light, "toggle")
        client.command(light, LightCommands.setBrightness(0.0))
        scheduler.runCurrent()
        assertEquals(Snapshot.Present(lamp(on = false, brightness = 0.4)), seen.last())
        assertNull(runtime.commands[1].third)
    }

    @Test
    fun `players change as the mock does`() {
        val ref = EntityRef("ma:kitchen")
        runtime.loadFixtures()
        connect()
        val seen = ArrayList<Snapshot>()
        client.watch(ref) { seen += it }
        client.command(ref, MediaPlayerCommands.togglePlay())
        client.command(ref, MediaPlayerCommands.setVolume(1.5))
        client.command(ref, MediaPlayerCommands.setMuted(false))
        client.command(ref, MediaPlayerCommands.seek(12.0))
        scheduler.wall = 1_772_366_400_000L
        scheduler.runCurrent()
        val player = (seen.last() as Snapshot.Present).entity as MediaPlayerEntity
        assertEquals(PlaybackState.Playing, player.playback)
        assertEquals(1.0, player.volume!!, 0.0)
        assertFalse(player.muted)
        assertEquals(12.0, player.position!!, 0.0)
        assertNotNull(Iso8601.parse(player.positionUpdatedAt))
    }

    @Test
    fun `switches, heaters and actions change`() {
        runtime.loadFixtures()
        connect()
        val plug = EntityRef("ha:switch.fx_on")
        val heater = EntityRef("ha:climate.fx_heating")
        val scene = EntityRef("ha:action.fx_ready")
        client.command(plug, SwitchCommands.toggle())
        client.command(heater, ClimateCommands.setTargetTemperature(19.5))
        client.command(heater, ClimateCommands.setMode(ClimateMode.Off))
        client.command(scene, ActionCommands.trigger())
        scheduler.runCurrent()
        assertFalse((runtime.entities.getValue(plug) as SwitchEntity).on)
        assertEquals(19.5, (runtime.entities.getValue(heater) as ClimateEntity).targetTemperature!!, 0.0)
        assertEquals(ClimateMode.Off, (runtime.entities.getValue(heater) as ClimateEntity).mode)
        assertNotNull((runtime.entities.getValue(scene) as ActionEntity).lastTriggered)
    }

    @Test
    fun `a command can be scripted to fail, once`() {
        runtime.put(lamp())
        connect()
        val outcomes = ArrayList<Outcome>()
        runtime.answerNext("The lamp is unplugged")
        client.command(light, "turnOn") { outcomes += it }
        client.command(light, "turnOn") { outcomes += it }
        scheduler.runCurrent()
        assertEquals(listOf(Outcome.Failed("The lamp is unplugged"), Outcome.Ok), outcomes)
        assertTrue((runtime.entities.getValue(light) as LightEntity).on)
        assertEquals(2, runtime.commands.size)
    }

    @Test
    fun `a bad command, or one for nothing, fails the way the runtime says`() {
        runtime.put(lamp())
        connect()
        val outcomes = ArrayList<Outcome>()
        client.command(light, "setBrightness", JSONObject().put("brightness", "bright")) { outcomes += it }
        client.command(light, "explode") { outcomes += it }
        client.command(EntityRef("ha:nothing"), "toggle") { outcomes += it }
        scheduler.runCurrent()
        assertTrue(outcomes.all { it is Outcome.Failed })
        assertEquals("\"setBrightness\" needs a numeric \"brightness\"", (outcomes[0] as Outcome.Failed).error)
    }

    @Test
    fun `queries are answered from the fixtures, or from what the test says`() {
        runtime.loadFixtures()
        connect()
        val player = EntityRef("ma:living_room")
        var browse: Result<BrowseResult>? = null
        var search: Result<BrowseResult>? = null
        var queue: Result<QueueResult>? = null
        var forecast: Result<ForecastResult>? = null
        var history: Result<HistoryResult>? = null
        var logbook: Result<LogbookResult>? = null
        client.query(player, Query.Browse()) { browse = it }
        client.query(player, Query.Browse(search = "mgmt")) { search = it }
        client.query(player, Query.Queue()) { queue = it }
        client.query(EntityRef("ha:weather.fx_ready"), Query.Forecast(ForecastType.TwiceDaily)) { forecast = it }
        client.query(EntityRef("ha:sensor.temperature"), Query.History(HistoryRange.Day)) { history = it }
        client.query(EntityRef("ha:light.plain"), Query.Logbook()) { logbook = it }
        scheduler.runCurrent()
        assertEquals("recent", browse!!.getOrThrow().items.first().id)
        assertTrue(search!!.getOrThrow().title!!.startsWith("Results for"))
        assertEquals(24, queue!!.getOrThrow().total)
        assertEquals(ForecastType.TwiceDaily, forecast!!.getOrThrow().type)
        assertTrue(history!!.getOrThrow().points.isNotEmpty())
        assertTrue(logbook!!.getOrThrow().entries.isNotEmpty())

        runtime.answer("queue", JSONObject("""{"items":[],"total":0,"offset":0}"""))
        client.query(player, Query.Queue()) { queue = it }
        client.query(EntityRef("ha:nothing"), Query.Queue()) { queue = it }
        scheduler.runCurrent()
        assertTrue(queue!!.isFailure)
    }

    @Test
    fun `a dropped link is reconnected and everything watched is subscribed again`() {
        runtime.put(lamp())
        connect()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        scheduler.runCurrent()
        runtime.drop()
        scheduler.runCurrent()
        assertEquals(LinkStatus.Closed, client.link.value)
        runtime.put(lamp(on = true))
        scheduler.advance(500)
        assertEquals(LinkStatus.Open, client.link.value)
        assertEquals(Snapshot.Present(lamp(on = true)), seen.last())
        assertEquals(1, runtime.connections.size)
    }

    @Test
    fun `a silent runtime is found dead once it has answered a ping`() {
        runtime.put(lamp())
        connect()
        scheduler.advance(5_000)
        assertEquals(LinkStatus.Open, client.link.value)
        val states = ArrayList<LinkStatus>()
        client.link.listen { states += it }
        runtime.silent = true
        scheduler.advance(30_000)
        assertTrue("heard to answer once, then not at all: dead", LinkStatus.Closed in states)
    }

    @Test
    fun `entities encode back to the wire shape`() {
        val frame = JSONObject(EntityJson.frame(light, lamp(on = true, brightness = 0.5)))
        assertEquals("entity", frame.getString("type"))
        val entity = frame.getJSONObject("entity")
        assertEquals("light", entity.getString("kind"))
        assertEquals(0.5, entity.getDouble("brightness"), 0.0)
        assertFalse(entity.has("color"))
        assertTrue(JSONObject(EntityJson.frame(light, null)).isNull("entity"))
    }

    @Test
    fun `fixtures are all there`() {
        assertTrue(Fixtures.inFolder("entities").size > 40)
        assertTrue(Fixtures.inFolder("results").size >= 18)
        assertEquals(4, Fixtures.inFolder("status").size)
    }
}

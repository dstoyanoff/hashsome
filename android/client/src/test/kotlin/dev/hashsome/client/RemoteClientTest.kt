package dev.hashsome.client

import dev.hashsome.testing.FakeTransport
import dev.hashsome.testing.TestScheduler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RemoteClient] over a scripted [FakeTransport] and a clock that moves only when the test says so. */
class RemoteClientTest {
    private val scheduler = TestScheduler()
    private val factory = FakeTransport.Factory()
    private val client = RemoteClient(ServerAddress("http://runtime.test:3000"), factory, scheduler)
    private val light = EntityRef("ha:light.x")

    private val transport: FakeTransport get() = factory.created.last()

    private fun lightFrame(on: Boolean, ref: String = light.value) =
        """{"type":"entity","ref":"$ref","entity":{"kind":"light","name":"X","availability":"ready","on":$on,"capabilities":{"brightness":false,"colorTemperature":false,"color":false},"ref":"$ref"}}"""

    private fun lightEntity(on: Boolean, ref: EntityRef = light) =
        LightEntity(ref, "X", Availability.Ready, on, capabilities = LightCapabilities(brightness = false, colorTemperature = false, color = false))

    /** Connects, opens the transport and lets the client hear about it. */
    private fun open() {
        client.connect()
        transport.open()
        scheduler.runCurrent()
    }

    private fun sentTypes(t: FakeTransport = transport) = t.sent.map { JSONObject(it).getString("type") }

    private fun frames(type: String, t: FakeTransport = transport) = t.sentOf(type)

    private fun result(id: Int, data: String? = null) = """{"type":"result","id":$id,"ok":true${if (data != null) ""","data":$data""" else ""}}"""

    private fun failure(id: Int, error: String) = """{"type":"result","id":$id,"ok":false,"error":"$error"}"""

    // --- The link -------------------------------------------------------------------------------------------------------

    @Test
    fun `connecting makes one transport for the ws path and opens the link when it opens`() {
        assertEquals(LinkStatus.Connecting, client.link.value)
        client.connect()
        client.connect()
        assertEquals(1, factory.created.size)
        assertEquals("/ws", transport.target!!.second)
        assertEquals("runtime.test", transport.target!!.first.host)
        assertTrue(transport.connected)
        assertEquals(LinkStatus.Connecting, client.link.value)
        transport.open()
        assertEquals("the link changes on the scheduler, not on the socket's thread", LinkStatus.Connecting, client.link.value)
        scheduler.runCurrent()
        assertEquals(LinkStatus.Open, client.link.value)
    }

    @Test
    fun `nothing a transport says reaches a caller until the scheduler runs`() {
        open()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        scheduler.runCurrent()
        seen.clear()
        transport.receive(lightFrame(true))
        assertTrue(seen.isEmpty())
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Present(lightEntity(true))), seen)
    }

    @Test
    fun `status messages feed the integrations store`() {
        open()
        val seen = ArrayList<Map<String, ConnectionStatus>>()
        client.integrations.listen { seen += it }
        transport.receive("""{"type":"status","integration":"ha","status":"connecting"}""")
        transport.receive("""{"type":"status","integration":"ma","status":"connected"}""")
        transport.receive("""{"type":"status","integration":"ha","status":"connected"}""")
        scheduler.runCurrent()
        assertEquals(mapOf("ha" to ConnectionStatus.Connected, "ma" to ConnectionStatus.Connected), client.integrations.value)
        assertEquals(3, seen.size)
        assertEquals(mapOf("ha" to ConnectionStatus.Connecting), seen[0])
    }

    @Test
    fun `an old transport can no longer speak to the client`() {
        open()
        val old = transport
        old.drop("gone")
        scheduler.runCurrent()
        scheduler.advance(500)
        val fresh = transport
        fresh.open()
        scheduler.runCurrent()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        scheduler.runCurrent()
        old.receive(lightFrame(true))
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Loading), seen)
    }

    // --- Watching -------------------------------------------------------------------------------------------------------

    @Test
    fun `a watch is told Loading, then what the runtime says`() {
        open()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Loading), seen)
        transport.receive(lightFrame(true))
        transport.receive(lightFrame(false))
        scheduler.runCurrent()
        assertEquals(listOf(Snapshot.Loading, Snapshot.Present(lightEntity(true)), Snapshot.Present(lightEntity(false))), seen)
    }

    @Test
    fun `an entity that is already known is delivered at once to a new watch`() {
        open()
        client.watch(light) {}
        transport.receive(lightFrame(true))
        scheduler.runCurrent()
        val late = ArrayList<Snapshot>()
        client.watch(light) { late += it }
        assertTrue("delivered through the scheduler, not inside watch()", late.isEmpty())
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Present(lightEntity(true))), late)
    }

    @Test
    fun `an entity that does not exist is Missing`() {
        open()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        transport.receive("""{"type":"entity","ref":"ha:light.x","entity":null}""")
        scheduler.runCurrent()
        assertEquals(Snapshot.Missing, seen.last())
        val late = ArrayList<Snapshot>()
        client.watch(light) { late += it }
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Missing), late)
    }

    @Test
    fun `any number of watches share one subscription, and the last to go ends it`() {
        open()
        val a = client.watch(light) {}
        val b = client.watch(light) {}
        val other = client.watch(EntityRef("ha:light.y")) {}
        assertEquals(listOf("subscribe", "subscribe"), sentTypes())
        assertEquals(listOf("ha:light.x", "ha:light.y"), frames("subscribe").map { it.getString("ref") })
        a()
        a()
        assertEquals(2, frames("subscribe").size)
        assertTrue(frames("unsubscribe").isEmpty())
        b()
        assertEquals(listOf("ha:light.x"), frames("unsubscribe").map { it.getString("ref") })
        other()
        assertEquals(2, frames("unsubscribe").size)
    }

    @Test
    fun `a cancelled watch hears nothing more, even from an update already on its way`() {
        open()
        val seen = ArrayList<Snapshot>()
        val cancel = client.watch(light) { seen += it }
        transport.receive(lightFrame(true))
        cancel()
        scheduler.runCurrent()
        assertTrue(seen.isEmpty())
        transport.receive(lightFrame(false))
        scheduler.runCurrent()
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `an unchanged entity does not notify again`() {
        open()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        repeat(3) { transport.receive(lightFrame(true)) }
        scheduler.runCurrent()
        assertEquals(2, seen.size)
    }

    @Test
    fun `watching before the link opens subscribes when it does, and again after a drop`() {
        client.connect()
        val b = client.watch(EntityRef("ha:b")) {}
        client.watch(EntityRef("ha:a")) {}
        assertTrue(transport.sent.isEmpty())
        transport.open()
        scheduler.runCurrent()
        assertEquals(listOf("ha:b", "ha:a"), frames("subscribe").map { it.getString("ref") })
        b()
        transport.drop()
        scheduler.runCurrent()
        scheduler.advance(500)
        val fresh = transport
        fresh.open()
        scheduler.runCurrent()
        assertEquals(listOf("ha:a"), frames("subscribe", fresh).map { it.getString("ref") })
    }

    @Test
    fun `what is known is kept across a reconnect`() {
        open()
        client.watch(light) {}
        transport.receive(lightFrame(true))
        scheduler.runCurrent()
        transport.drop()
        scheduler.runCurrent()
        val late = ArrayList<Snapshot>()
        client.watch(light) { late += it }
        scheduler.runCurrent()
        assertEquals(listOf<Snapshot>(Snapshot.Present(lightEntity(true))), late)
    }

    // --- Commands -------------------------------------------------------------------------------------------------------

    @Test
    fun `commands get increasing ids and hear how they ended`() {
        open()
        val outcomes = ArrayList<String>()
        client.command(light, "toggle") { outcomes += "1:$it" }
        client.command(light, "setBrightness", JSONObject().put("brightness", 0.4)) { outcomes += "2:$it" }
        val sent = frames("command")
        assertEquals(listOf(1, 2), sent.map { it.getInt("id") })
        assertEquals("toggle", sent[0].getString("command"))
        assertEquals("ha:light.x", sent[0].getString("ref"))
        assertFalse(sent[0].has("args"))
        assertEquals(0.4, sent[1].getJSONObject("args").getDouble("brightness"), 0.0)
        transport.receive(failure(2, "Refused"))
        transport.receive(result(1))
        scheduler.runCurrent()
        assertEquals(listOf("2:Failed(error=Refused)", "1:Ok"), outcomes)
    }

    @Test
    fun `a typed command is the same as the string form`() {
        open()
        client.command(light, LightCommands.setBrightness(0.25))
        client.command(light, SwitchCommands.toggle())
        val sent = frames("command")
        assertEquals("setBrightness", sent[0].getString("command"))
        assertEquals(0.25, sent[0].getJSONObject("args").getDouble("brightness"), 0.0)
        assertEquals("toggle", sent[1].getString("command"))
    }

    @Test
    fun `a command without a callback is fine`() {
        open()
        client.command(light, "toggle")
        transport.receive(result(1))
        scheduler.runCurrent()
    }

    @Test
    fun `cancelling a command only stops its callback`() {
        open()
        var heard = false
        val cancel = client.command(light, "toggle") { heard = true }
        cancel()
        assertEquals(1, frames("command").size)
        transport.receive(result(1))
        scheduler.runCurrent()
        assertFalse(heard)
    }

    @Test
    fun `an answer for a request nobody made is ignored`() {
        open()
        transport.receive(result(99))
        scheduler.runCurrent()
    }

    @Test
    fun `a command made while the link is not open fails at once`() {
        var outcome: Outcome? = null
        client.command(light, "toggle") { outcome = it }
        assertNull(outcome)
        scheduler.runCurrent()
        assertEquals(Outcome.Failed("Not connected to runtime"), outcome)
        client.connect()
        outcome = null
        client.command(light, "toggle") { outcome = it }
        scheduler.runCurrent()
        assertEquals(Outcome.Failed("Not connected to runtime"), outcome)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `requests in flight fail with Connection lost when the link drops`() {
        open()
        val outcomes = ArrayList<Any>()
        client.command(light, "toggle") { outcomes += it }
        client.query(light, Query.Queue()) { outcomes += it.exceptionOrNull()!!.message!! }
        transport.drop("reset")
        assertTrue(outcomes.isEmpty())
        scheduler.runCurrent()
        assertEquals(listOf(Outcome.Failed("Connection lost"), "Connection lost"), outcomes)
        assertEquals(LinkStatus.Closed, client.link.value)
    }

    @Test
    fun `an answer that comes after the drop is for nobody`() {
        open()
        var calls = 0
        client.command(light, "toggle") { calls++ }
        val old = transport
        old.drop()
        scheduler.runCurrent()
        old.receive(result(1))
        scheduler.runCurrent()
        assertEquals(1, calls)
    }

    // --- Queries --------------------------------------------------------------------------------------------------------

    @Test
    fun `a query is sent with its arguments and answered with the parsed result`() {
        open()
        var got: Result<QueueResult>? = null
        client.query(EntityRef("ma:p"), Query.Queue(limit = 5, duration = true)) { got = it }
        val sent = frames("query").single()
        assertEquals("queue", sent.getString("query"))
        assertEquals("ma:p", sent.getString("ref"))
        assertEquals(5, sent.getJSONObject("args").getInt("limit"))
        transport.receive(result(sent.getInt("id"), """{"items":[{"id":"a","title":"A","current":true}],"total":9,"offset":3,"duration":120}"""))
        scheduler.runCurrent()
        assertEquals(QueueResult(listOf(QueueItem("a", "A", current = true)), 9, 3, 120.0), got!!.getOrThrow())
    }

    @Test
    fun `a query without arguments sends none`() {
        open()
        client.query(EntityRef("ma:p"), Query.Browse()) {}
        assertFalse(frames("query").single().has("args"))
    }

    @Test
    fun `a query that fails, or whose answer cannot be read, is a failure`() {
        open()
        val seen = ArrayList<Result<*>>()
        client.query(light, Query.Logbook()) { seen += it }
        client.query(light, Query.Logbook()) { seen += it }
        transport.receive(failure(1, "keeps no activity"))
        transport.receive(result(2))
        scheduler.runCurrent()
        assertEquals("keeps no activity", seen[0].exceptionOrNull()!!.message)
        assertTrue(seen[1].isFailure)
    }

    @Test
    fun `a query made before the link opens goes out when it does`() {
        client.connect()
        var got: Result<LogbookResult>? = null
        client.query(light, Query.Logbook(3)) { got = it }
        assertTrue(transport.sent.isEmpty())
        scheduler.advance(2_000)
        assertNull(got)
        transport.open()
        scheduler.runCurrent()
        val sent = frames("query").single()
        transport.receive(result(sent.getInt("id"), """{"entries":[]}"""))
        scheduler.runCurrent()
        assertEquals(LogbookResult(emptyList()), got!!.getOrThrow())
        scheduler.advance(20_000)
        assertEquals("the wait was over, so no late failure", 1, frames("query").size)
    }

    @Test
    fun `a query that waits too long for the link fails`() {
        client.connect()
        var got: Result<LogbookResult>? = null
        client.query(light, Query.Logbook()) { got = it }
        scheduler.advance(RemoteClient.QUERY_WAIT_MS - 1)
        assertNull(got)
        scheduler.advance(1)
        assertEquals("Not connected to runtime", got!!.exceptionOrNull()!!.message)
        transport.open()
        scheduler.runCurrent()
        assertTrue(frames("query").isEmpty())
    }

    @Test
    fun `a query made when the client was never connected fails at once`() {
        var got: Result<LogbookResult>? = null
        client.query(light, Query.Logbook()) { got = it }
        scheduler.runCurrent()
        assertEquals("Not connected to runtime", got!!.exceptionOrNull()!!.message)
    }

    @Test
    fun `a waiting query can be cancelled, and is given up on when the client closes`() {
        client.connect()
        var calls = 0
        val cancel = client.query(light, Query.Logbook()) { calls++ }
        cancel()
        client.query(light, Query.Logbook()) { calls += 10 }
        client.close()
        scheduler.advance(30_000)
        assertEquals(10, calls)
    }

    // --- Heartbeat ------------------------------------------------------------------------------------------------------

    @Test
    fun `a ping goes out every five seconds`() {
        open()
        assertTrue(frames("ping").isEmpty())
        scheduler.advance(4_999)
        assertTrue(frames("ping").isEmpty())
        scheduler.advance(1)
        assertEquals(1, frames("ping").size)
        scheduler.advance(10_000)
        assertEquals(3, frames("ping").size)
    }

    @Test
    fun `a runtime that never answers a ping is never taken for dead`() {
        open()
        scheduler.advance(10 * 60_000)
        assertEquals(LinkStatus.Open, client.link.value)
        assertEquals(1, factory.created.size)
        assertEquals(120, frames("ping").size)
    }

    @Test
    fun `a runtime that went quiet after answering is found dead and reopened`() {
        open()
        scheduler.advance(5_000)
        transport.receive("""{"type":"pong"}""")
        scheduler.runCurrent()
        // Silent since t = 5 s: the beat at 20 s sees 15 s of it, which is not yet more than 15; the one at 25 s does.
        scheduler.advance(19_999)
        assertEquals(LinkStatus.Open, client.link.value)
        scheduler.advance(1)
        assertEquals(LinkStatus.Closed, client.link.value)
        assertTrue("the dead transport is closed", factory.created[0].closedByClient)
        scheduler.advance(500)
        assertEquals(2, factory.created.size)
        assertEquals(LinkStatus.Connecting, client.link.value)
    }

    @Test
    fun `a runtime that keeps answering is kept`() {
        open()
        repeat(20) {
            scheduler.advance(5_000)
            transport.receive("""{"type":"pong"}""")
            scheduler.runCurrent()
        }
        assertEquals(LinkStatus.Open, client.link.value)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `any message counts as being heard`() {
        open()
        scheduler.advance(5_000)
        transport.receive("""{"type":"pong"}""")
        repeat(12) {
            scheduler.advance(5_000)
            transport.receive("""{"type":"status","integration":"ha","status":"connected"}""")
            scheduler.runCurrent()
        }
        assertEquals(LinkStatus.Open, client.link.value)
    }

    @Test
    fun `frames that mean nothing are not hearing from the runtime`() {
        open()
        scheduler.advance(5_000)
        transport.receive("""{"type":"pong"}""")
        repeat(6) {
            scheduler.advance(5_000)
            transport.receive("garbage")
            transport.receive("""{"type":"mystery"}""")
            scheduler.runCurrent()
        }
        assertTrue(factory.created[0].closedByClient)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `the beat stops with the connection`() {
        open()
        transport.drop()
        scheduler.runCurrent()
        val before = frames("ping").size
        scheduler.advance(499)
        scheduler.advance(30_000)
        assertEquals(before, frames("ping", factory.created[0]).size)
    }

    // --- Reconnecting ---------------------------------------------------------------------------------------------------

    @Test
    fun `reconnects wait 500, 1000, 2000, 4000 and then 4000 again`() {
        client.connect()
        var expected = 1
        for (wait in listOf(500L, 1_000L, 2_000L, 4_000L, 4_000L, 4_000L)) {
            transport.drop("refused")
            scheduler.runCurrent()
            assertEquals(LinkStatus.Closed, client.link.value)
            scheduler.advance(wait - 1)
            assertEquals("before $wait ms", expected, factory.created.size)
            scheduler.advance(1)
            expected++
            assertEquals("after $wait ms", expected, factory.created.size)
            assertEquals(LinkStatus.Connecting, client.link.value)
        }
    }

    @Test
    fun `a connection that opened starts the backoff over`() {
        client.connect()
        repeat(3) {
            transport.drop()
            scheduler.runCurrent()
            scheduler.advance(4_000)
        }
        transport.open()
        scheduler.runCurrent()
        assertEquals(LinkStatus.Open, client.link.value)
        transport.drop()
        scheduler.runCurrent()
        val count = factory.created.size
        scheduler.advance(499)
        assertEquals(count, factory.created.size)
        scheduler.advance(1)
        assertEquals(count + 1, factory.created.size)
    }

    @Test
    fun `a close and a dead link reported together reconnect once`() {
        open()
        val t = transport
        scheduler.advance(5_000)
        t.receive("""{"type":"pong"}""")
        scheduler.advance(20_000)
        assertEquals(LinkStatus.Closed, client.link.value)
        t.drop("late report from the dead socket")
        scheduler.runCurrent()
        scheduler.advance(10_000)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `closing stops everything for good`() {
        open()
        client.watch(light) {}
        var outcome: Outcome? = null
        client.command(light, "toggle") { outcome = it }
        client.close()
        assertEquals(LinkStatus.Closed, client.link.value)
        assertTrue(transport.closedByClient)
        scheduler.runCurrent()
        assertEquals(Outcome.Failed("Connection lost"), outcome)
        scheduler.advance(120_000)
        assertEquals(1, factory.created.size)
        assertEquals(LinkStatus.Closed, client.link.value)
    }

    @Test
    fun `a closed client can connect again`() {
        open()
        client.watch(light) {}
        client.close()
        client.connect()
        assertEquals(2, factory.created.size)
        transport.open()
        scheduler.runCurrent()
        assertEquals(LinkStatus.Open, client.link.value)
        assertEquals(listOf("ha:light.x"), frames("subscribe").map { it.getString("ref") })
    }

    @Test
    fun `a transport that dies while connecting is retried`() {
        client.connect()
        transport.drop("refused")
        scheduler.runCurrent()
        scheduler.advance(500)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `an entity body that cannot be read degrades to a generic entity and is logged`() {
        val logged = ArrayList<String>()
        client.log = { logged += it }
        client.parseEntityBody = { _, _ -> throw IllegalStateException("broken body") }
        open()
        val seen = ArrayList<Snapshot>()
        client.watch(light) { seen += it }
        transport.receive(lightFrame(true))
        scheduler.runCurrent()
        val entity = (seen.last() as Snapshot.Present).entity as GenericEntity
        assertEquals("X", entity.name)
        assertEquals(Availability.Ready, entity.availability)
        assertEquals(1, logged.size)
        assertTrue(logged[0], logged[0].contains("ha:light.x") && logged[0].contains("broken body"))
    }

    @Test
    fun `a number that is not finite fails the command instead of throwing`() {
        open()
        val outcomes = ArrayList<Outcome>()
        client.command(light, LightCommands.setBrightness(Double.NaN)) { outcomes += it }
        client.command(light, LightCommands.setColor(Double.POSITIVE_INFINITY, 50.0)) { outcomes += it }
        client.command(light, MediaPlayerCommands.seek(Double.NEGATIVE_INFINITY)) { outcomes += it }
        assertTrue("nothing is sent", frames("command").isEmpty())
        scheduler.runCurrent()
        assertEquals(
            listOf(
                Outcome.Failed("Invalid argument \"brightness\" for setBrightness"),
                Outcome.Failed("Invalid argument \"hue\" for setColor"),
                Outcome.Failed("Invalid argument \"position\" for seek"),
            ),
            outcomes,
        )
        client.command(light, LightCommands.setBrightness(0.5))
        assertEquals(1, frames("command").size)
    }
}

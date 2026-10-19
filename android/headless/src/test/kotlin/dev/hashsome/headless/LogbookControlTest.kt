package dev.hashsome.headless

import dev.hashsome.client.ActorKind
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.LogbookChange
import dev.hashsome.client.LogbookEntry
import dev.hashsome.client.LogbookResult
import dev.hashsome.client.Query
import dev.hashsome.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// use-entity-logbook.ts
class LogbookControlTest {
    private val rig = Rig()
    private val limits = ArrayList<Int?>()

    private val entry = LogbookEntry("1", "turned on", "2026-06-01T12:00:00Z", "Dana", ActorKind.Person, LogbookChange.On)

    init {
        rig.client.answerWith(ref("lamp"), "logbook") { q ->
            limits += (q as Query.Logbook).limit
            LogbookResult(listOf(entry))
        }
    }

    private fun control(scope: Scope = rig.scope) = LogbookControl(ref("lamp"), scope)

    @Test
    fun `nothing is asked until it is opened`() {
        val l = control()
        rig.settle()
        assertEquals(LogbookState.Idle, l.state.value)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `opening asks for six entries, loading, then ready`() {
        val l = control()
        l.open()
        assertEquals(LogbookState.Loading, l.state.value)
        rig.settle()
        assertEquals(LogbookState.Ready(listOf(entry)), l.state.value)
        assertEquals(listOf<Int?>(6), limits)
    }

    @Test
    fun `opening again does nothing`() {
        val l = control()
        l.open()
        rig.settle()
        l.open()
        rig.settle()
        assertEquals(1, limits.size)
    }

    @Test
    fun `an answer is reused for half a minute`() {
        control().open()
        rig.settle()
        rig.scheduler.advance(29_999)
        val again = control()
        again.open()
        assertEquals(LogbookState.Ready(listOf(entry)), again.state.value)
        rig.settle()
        assertEquals(1, limits.size)
        rig.scheduler.advance(1)
        control().open()
        rig.settle()
        assertEquals(2, limits.size)
        assertEquals(30_000L, LogbookControl.FRESH_MS)
    }

    @Test
    fun `a failed answer is shown as failed and not kept`() {
        rig.client.failQuery("Not connected")
        val l = control()
        l.open()
        rig.settle()
        assertEquals(LogbookState.Failed("Not connected"), l.state.value)
        rig.client.failQuery(null)
        control().open()
        rig.settle()
        assertEquals(1, limits.size)
    }

    @Test
    fun `it asks again when the connection is back, but only if it was opened`() {
        val unopened = control()
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(LogbookState.Idle, unopened.state.value)

        rig.client.link.set(LinkStatus.Closed)
        rig.client.failQuery("Connection lost")
        val l = control()
        l.open()
        rig.settle()
        assertTrue(l.state.value is LogbookState.Failed)
        rig.client.failQuery(null)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(LogbookState.Ready(listOf(entry)), l.state.value)
    }

    @Test
    fun `the fixtures parse`() {
        rig.client.answer(ref("full"), "logbook", Fixtures.json("results/logbook.json").getJSONObject("data"))
        rig.client.answer(ref("quiet"), "logbook", Fixtures.json("results/logbook-empty.json").getJSONObject("data"))
        val full = LogbookControl(ref("full"), rig.scope).also { it.open() }
        val quiet = LogbookControl(ref("quiet"), rig.scope).also { it.open() }
        rig.settle()
        assertTrue((full.state.value as LogbookState.Ready).entries.isNotEmpty())
        assertEquals(LogbookState.Ready(emptyList()), quiet.state.value)
    }

    @Test
    fun `no entity behind it is never asked`() {
        val l = LogbookControl(null as dev.hashsome.client.EntityRef?, rig.scope)
        l.open()
        assertEquals(LogbookState.Idle, l.state.value)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `nothing is shown after the scope is disposed`() {
        val l = control()
        l.open()
        rig.scope.dispose()
        rig.settle()
        assertEquals(LogbookState.Loading, l.state.value)
    }
}

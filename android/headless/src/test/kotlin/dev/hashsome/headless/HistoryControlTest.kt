package dev.hashsome.headless

import dev.hashsome.client.HistoryBucket
import dev.hashsome.client.HistoryKind
import dev.hashsome.client.HistoryPoint
import dev.hashsome.client.HistoryRange
import dev.hashsome.client.HistoryResult
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Query
import dev.hashsome.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// use-entity-history.ts
class HistoryControlTest {
    private val rig = Rig()
    private val asked = ArrayList<Pair<HistoryRange, HistoryBucket?>>()

    private fun historyOf(range: HistoryRange) =
        HistoryResult(listOf(HistoryPoint("2026-06-01T12:00:00Z", range.ordinal + 1.0)), HistoryKind.Measurement, "°C")

    init {
        rig.client.answerWith(ref("temp"), "history") { q ->
            val query = q as Query.History
            asked += query.range to query.bucket
            historyOf(query.range)
        }
    }

    private fun control(range: HistoryRange = HistoryRange.Day, bucket: HistoryBucket? = null, scope: Scope = rig.scope) =
        HistoryControl(ref("temp"), scope, range, bucket)

    @Test
    fun `it is loading, then ready`() {
        val h = control()
        assertEquals(HistoryState.Loading, h.state.value)
        rig.settle()
        assertEquals(HistoryState.Ready(historyOf(HistoryRange.Day)), h.state.value)
    }

    @Test
    fun `the range and the bucket are what is asked`() {
        control(HistoryRange.Month, HistoryBucket.Hour)
        control(HistoryRange.Week, HistoryBucket.FiveMinutes)
        rig.settle()
        assertEquals(listOf(HistoryRange.Month to HistoryBucket.Hour, HistoryRange.Week to HistoryBucket.FiveMinutes), asked)
    }

    @Test
    fun `an answer is reused for a minute, per range and bucket`() {
        control()
        rig.settle()
        rig.scheduler.advance(59_999)
        val again = control()
        assertEquals(HistoryState.Ready(historyOf(HistoryRange.Day)), again.state.value)
        rig.settle()
        assertEquals(1, asked.size)

        // Another range or another bucket is another question.
        control(HistoryRange.Week)
        control(HistoryRange.Day, HistoryBucket.Hour)
        rig.settle()
        assertEquals(3, asked.size)

        rig.scheduler.advance(1)
        control()
        rig.settle()
        assertEquals(4, asked.size)
        assertEquals(60_000L, HistoryControl.FRESH_MS)
    }

    @Test
    fun `two asks at once share one request`() {
        val a = control()
        val b = control()
        rig.settle()
        assertEquals(1, asked.size)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `the answer is shared with another screen, not with another client`() {
        control()
        rig.settle()
        val second = control(scope = rig.scope.child())
        assertEquals(HistoryState.Ready(historyOf(HistoryRange.Day)), second.state.value)

        val other = Rig()
        other.client.answerValue(ref("temp"), "history", historyOf(HistoryRange.Day))
        assertEquals(HistoryState.Loading, HistoryControl(ref("temp"), other.scope, HistoryRange.Day).state.value)
    }

    @Test
    fun `a failed answer is shown as failed and not kept, so the next ask tries again`() {
        rig.client.failQuery("Not connected")
        val h = control()
        rig.settle()
        assertEquals(HistoryState.Failed("Not connected"), h.state.value)
        rig.client.failQuery(null)
        control().also { rig.settle() }
        assertEquals(1, asked.size)
    }

    @Test
    fun `it asks again when the connection is back`() {
        rig.client.link.set(LinkStatus.Closed)
        rig.client.failQuery("Connection lost")
        val h = control()
        rig.settle()
        assertTrue(h.state.value is HistoryState.Failed)
        rig.client.failQuery(null)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(HistoryState.Ready(historyOf(HistoryRange.Day)), h.state.value)
    }

    @Test
    fun `an answer that is there is not asked for again when the connection returns`() {
        control()
        rig.settle()
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(1, asked.size)
    }

    @Test
    fun `a backend that keeps no history gives a result with no points`() {
        rig.client.answer(ref("none"), "history", Fixtures.json("results/history-empty.json").getJSONObject("data"))
        val h = HistoryControl(ref("none"), rig.scope, HistoryRange.Day)
        rig.settle()
        assertEquals(HistoryState.Ready(HistoryResult(emptyList(), HistoryKind.Measurement)), h.state.value)
    }

    @Test
    fun `the fixtures parse into a measurement and a running total`() {
        rig.client.answer(ref("m"), "history", Fixtures.json("results/history-measurement.json").getJSONObject("data"))
        rig.client.answer(ref("t"), "history", Fixtures.json("results/history-total.json").getJSONObject("data"))
        val m = HistoryControl(ref("m"), rig.scope, HistoryRange.Day)
        val t = HistoryControl(ref("t"), rig.scope, HistoryRange.Month, HistoryBucket.Day)
        rig.settle()
        val measured = (m.state.value as HistoryState.Ready).result
        val total = (t.state.value as HistoryState.Ready).result
        assertEquals(HistoryKind.Measurement, measured.kind)
        assertEquals(HistoryKind.Total, total.kind)
        assertEquals(7, total.points.size)
        assertEquals("kWh", total.unit)
    }

    @Test
    fun `nothing is shown after the scope is disposed`() {
        val h = control()
        rig.scope.dispose()
        rig.settle()
        assertEquals(HistoryState.Loading, h.state.value)
    }

    @Test
    fun `no entity behind it is never asked`() {
        val h = HistoryControl(null as dev.hashsome.client.EntityRef?, rig.scope, HistoryRange.Day)
        assertEquals(HistoryState.Idle, h.state.value)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `a long-lived scope does not collect a registration per answer`() {
        val before = rig.scope.registered
        control()
        rig.settle()
        // Only the reconnect listener stays.
        assertEquals(before + 1, rig.scope.registered)
    }
}

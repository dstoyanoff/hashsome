package dev.hashsome.headless

import dev.hashsome.client.EntityKind
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlayMode
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Query
import dev.hashsome.client.QueueItem
import dev.hashsome.client.QueueResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// use-media-queue.ts and entities/__tests__/media-queue.test.tsx: the window, when it is read again, and the commands.
class MediaQueueTest {
    private val rig = Rig()
    private val asks = ArrayList<Query.Queue>()

    private val tracks = listOf("a", "b", "c").map { QueueItem(it, "Track $it", current = it == "b") }
    private var result = QueueResult(tracks, total = 40, offset = 7, duration = 7_980.0)

    private val queueCaps = noCaps.copy(queue = true)

    init {
        rig.client.onCommand = null
        rig.client.answerWith(ref("room"), "queue") { q ->
            asks += q as Query.Queue
            result
        }
    }

    private fun queue(p: MediaPlayerEntity = player(title = "Track b", caps = queueCaps)): QueueControl {
        rig.client.set(ref("room"), p)
        return QueueControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope).also { rig.settle() }
    }

    private val QueueControl.at get() = state.value

    @Test
    fun `lists the queue with its size, the window it starts at, and how long it all plays`() {
        val q = queue()
        assertEquals(tracks, q.at.items)
        assertEquals(40, q.at.total)
        assertEquals(7, q.at.offset)
        assertEquals(7_980.0, q.at.duration!!, 0.0)
        assertFalse(q.at.loading)
        assertNull(q.at.error)
    }

    @Test
    fun `it asks for the duration as well`() {
        queue()
        assertEquals(listOf(true), asks.map { it.duration })
    }

    @Test
    fun `it is loading until the first answer`() {
        rig.client.queryDelayMs = 100
        val q = queue()
        assertTrue(q.at.loading)
        assertEquals(emptyList<QueueItem>(), q.at.items)
        rig.scheduler.advance(100)
        assertFalse(q.at.loading)
        assertEquals(tracks, q.at.items)
    }

    @Test
    fun `a player with no queue shows nothing, and is not asked`() {
        val q = queue(player(title = "x"))
        assertEquals(QueueState(loading = false), q.at)
        assertEquals(0, rig.client.queries.size)
        rig.scheduler.advance(60_000)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `a player that is not there is not asked`() {
        val q = QueueControl(rig.handle(ref("ghost"), EntityKind.MediaPlayer), rig.scope)
        rig.settle()
        assertEquals(QueueState(loading = false), q.at)
        assertEquals(0, rig.client.queries.size)
    }

    @Test
    fun `it reads the queue again when the track changes, or the order it plays in`() {
        val q = queue()
        assertEquals(1, asks.size)
        rig.client.set(ref("room"), player(title = "Track c", caps = queueCaps))
        rig.settle()
        assertEquals(2, asks.size)
        rig.client.set(ref("room"), player(title = "Track c", caps = queueCaps, shuffle = true))
        rig.settle()
        assertEquals(3, asks.size)
        assertFalse(q.at.loading)
    }

    @Test
    fun `it does not read it again for what does not move the window`() {
        queue()
        rig.client.set(ref("room"), player(title = "Track b", caps = queueCaps, position = 30.0, volume = 0.9, playback = PlaybackState.Playing))
        rig.settle()
        rig.client.set(ref("room"), player(title = "Track b", caps = queueCaps, volume = 0.1))
        rig.settle()
        assertEquals(1, asks.size)
    }

    @Test
    fun `it reads the queue again every 15 seconds while it is on screen`() {
        queue()
        rig.scheduler.advance(14_999)
        assertEquals(1, asks.size)
        rig.scheduler.advance(1)
        rig.settle()
        assertEquals(2, asks.size)
        rig.scheduler.advance(30_000)
        assertEquals(4, asks.size)
        assertEquals(15_000L, QueueControl.POLL_MS)
    }

    @Test
    fun `the polling ends with the scope`() {
        queue()
        rig.scope.dispose()
        rig.scheduler.advance(60_000)
        assertEquals(1, asks.size)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `refresh reads it again`() {
        val q = queue()
        result = QueueResult(tracks.take(1), 1, 0)
        q.refresh()
        rig.settle()
        assertEquals(2, asks.size)
        assertEquals(1, q.at.total)
        assertFalse(q.at.loading)
        // Not a skeleton again: it keeps what it has while it reads.
    }

    @Test
    fun `it reads again when the connection is back`() {
        queue()
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(2, asks.size)
    }

    @Test
    fun `a read that fails leaves nothing to show and the next one recovers`() {
        val q = queue()
        rig.client.failQuery("Connection lost")
        q.refresh()
        rig.settle()
        assertEquals(QueueState(loading = false, error = "Connection lost"), q.at)
        rig.client.failQuery(null)
        rig.scheduler.advance(15_000)
        assertEquals(tracks, q.at.items)
        assertNull(q.at.error)
    }

    @Test
    fun `an answer to an older ask does not replace a newer one`() {
        // The first ask is slow; the second, made after it, is fast and is the one that counts.
        val answers = listOf(QueueResult(tracks.take(1), 1, 0), QueueResult(tracks, 3, 0))
        var n = 0
        rig.client.answerWith(ref("room"), "queue") { answers[n++] }
        rig.client.queryDelayMs = 500
        val q = queue()
        rig.client.queryDelayMs = 0
        q.refresh()
        rig.settle()
        // The fast one is the first to be answered, with the first answer; the slow one then arrives with the second.
        assertEquals(1, q.at.total)
        rig.scheduler.advance(500)
        assertEquals(1, q.at.total)
    }

    @Test
    fun `something added from the library reads the queue again`() {
        queue()
        QueueEpoch.bump(rig.client, ref("room"))
        rig.settle()
        assertEquals(2, asks.size)
    }

    @Test
    fun `playing from the library does too, when the player accepts it`() {
        val q = queue()
        val library = MediaBrowserControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        rig.client.answerWith(ref("room"), "browse") { dev.hashsome.client.BrowseResult(items = emptyList()) }
        rig.settle()
        val before = asks.size
        library.play(item("tr", kind = dev.hashsome.client.BrowseKind.Track), PlayMode.Add)
        rig.settle()
        assertEquals(before + 1, asks.size)
        assertFalse(q.at.loading)
    }

    // --- the commands ------------------------------------------------------------------------------------------

    private val sent get() = rig.client.commands.map { it.second }

    @Test
    fun `tapping a track jumps to it and the queue is read again a moment later`() {
        val q = queue()
        q.play(tracks[2])
        assertEquals(listOf("playQueueItem"), sent)
        assertEquals("c", rig.client.commands[0].third!!.getString("item"))
        rig.settle()
        assertEquals(1, asks.size)
        rig.scheduler.advance(399)
        assertEquals(1, asks.size)
        rig.scheduler.advance(1)
        rig.settle()
        assertEquals(2, asks.size)
    }

    @Test
    fun `a track can be taken out`() {
        val q = queue()
        q.remove(tracks[0])
        assertEquals(listOf("removeQueueItem"), sent)
        assertEquals("a", rig.client.commands[0].third!!.getString("item"))
    }

    @Test
    fun `Clear empties the queue`() {
        val q = queue()
        q.clear()
        assertEquals(listOf("clearQueue"), sent)
        assertNull(rig.client.commands[0].third)
    }

    @Test
    fun `a track is moved by the number of places`() {
        val q = queue()
        q.move(tracks[2], -2)
        q.move(tracks[0], 1)
        q.move(tracks[1], 0)
        assertEquals(listOf("moveQueueItem", "moveQueueItem"), sent)
        assertEquals(-2, rig.client.commands[0].third!!.getInt("shift"))
        assertEquals("c", rig.client.commands[0].third!!.getString("item"))
        assertEquals(1, rig.client.commands[1].third!!.getInt("shift"))
    }

    @Test
    fun `a command that fails does not read the queue again`() {
        val q = queue()
        rig.client.failNext("nope")
        q.remove(tracks[0])
        rig.settle()
        rig.scheduler.advance(1_000)
        assertEquals(1, asks.size)
    }
}

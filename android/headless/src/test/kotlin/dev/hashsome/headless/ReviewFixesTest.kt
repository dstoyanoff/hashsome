package dev.hashsome.headless

import dev.hashsome.client.BrowseKind
import dev.hashsome.client.BrowseResult
import dev.hashsome.client.EntityKind
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.Outcome
import dev.hashsome.client.PlayMode
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Query
import dev.hashsome.client.QueueItem
import dev.hashsome.client.QueueResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Behaviours the review of the first version asked to be pinned.
class ReviewFixesTest {
    private val rig = Rig()

    private fun browseWith(items: () -> BrowseResult) {
        rig.client.answerWith(ref("room"), "browse") { items() }
    }

    private fun browser(): MediaBrowserControl {
        rig.client.set(ref("room"), player(caps = noCaps.copy(browse = true, queue = true), title = "x"))
        return MediaBrowserControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope).also { rig.settle() }
    }

    @Test
    fun `a list that answered with an error opens its rows when the retry succeeds`() {
        val rows = (1..8).map { item("p$it", "P$it", BrowseKind.Track, expandable = false) }
        browseWith { BrowseResult(items = rows) }
        rig.client.failQuery("Not connected")
        val b = browser()
        assertEquals("Not connected", b.state.value.error)
        assertEquals(0, b.arrival.visible.value)
        rig.client.failQuery(null)
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(8, b.state.value.rows.size)
        assertEquals(1, b.arrival.visible.value)
        rig.scheduler.advance(2_000)
        assertEquals(8, b.arrival.visible.value)
    }

    @Test
    fun `the same list answering again with more rows keeps what is shown`() {
        var n = 8
        browseWith { BrowseResult(items = (1..n).map { item("p$it", "P$it", BrowseKind.Track, expandable = false) }) }
        val b = browser()
        rig.scheduler.advance(2_000)
        assertEquals(8, b.arrival.visible.value)
        n = 30
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(30, b.state.value.rows.size)
        assertEquals(8, b.arrival.visible.value)
        b.arrival.more()
        assertEquals(18, b.arrival.visible.value)
    }

    @Test
    fun `with motion off the first screenful is shown at once`() {
        val arrival = RowsArrival(rig.scope)
        arrival.stagger = false
        arrival.start(60)
        assertEquals(10, arrival.visible.value)
        arrival.start(6)
        assertEquals(6, arrival.visible.value)
        assertEquals(0, rig.scheduler.pending)
    }

    @Test
    fun `the open tab is kept by its path when the top level comes back in another order`() {
        val a = item("a", "A", BrowseKind.Folder, playable = false, expandable = true)
        val b = item("b", "B", BrowseKind.Folder, playable = false, expandable = true)
        val c = item("c", "C", BrowseKind.Folder, playable = false, expandable = true)
        var top = listOf(a, b, c)
        rig.client.answerWith(ref("room"), "browse") { q ->
            val path = (q as Query.Browse).path
            if (path == null) BrowseResult(items = top) else BrowseResult(items = listOf(item("in-$path")))
        }

        val browser = browser()
        browser.openTab("b")
        rig.settle()
        assertEquals(1, browser.state.value.active)
        top = listOf(c, b, a)
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(listOf("C", "B", "A"), browser.state.value.tabs.map { it.title })
        assertEquals(1, browser.state.value.active)
        assertEquals(listOf("in-b"), browser.state.value.rows.map { it.id })
        browser.openTab(2)
        rig.settle()
        assertEquals(listOf("in-a"), browser.state.value.rows.map { it.id })
    }

    // --- scope bookkeeping ---------------------------------------------------------------------------------------

    @Test
    fun `a long-lived scope does not collect a registration per answer`() {
        rig.client.set(ref("plug"), switch())
        rig.client.answerValue(ref("room"), "browse", BrowseResult(items = emptyList()))
        val before = rig.scope.registered
        repeat(50) {
            rig.scope.command(ref("plug"), "toggle", null) {}
            rig.scope.command(ref("plug"), "toggle", null, null)
            rig.scope.query(ref("room"), Query.Browse()) {}
        }

        rig.settle()
        assertEquals(before, rig.scope.registered)
    }

    @Test
    fun `a request still waiting is held, and cancelled with the scope`() {
        rig.client.set(ref("plug"), switch())
        var heard = 0
        rig.scope.command(ref("plug"), "toggle", null) { heard++ }
        assertEquals(1, rig.scope.registered)
        rig.scope.dispose()
        rig.settle()
        assertEquals(0, heard)
    }

    @Test
    fun `polling the queue for a long time collects nothing`() {
        rig.client.set(ref("room"), player(title = "x", caps = noCaps.copy(queue = true)))
        rig.client.answerValue(ref("room"), "queue", QueueResult(listOf(QueueItem("a", "A", current = true)), 1, 0))
        QueueControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        rig.settle()
        val before = rig.scope.registered
        rig.scheduler.advance(15_000L * 40)
        assertEquals(before, rig.scope.registered)
    }

    // --- one wait per player -------------------------------------------------------------------------------------

    @Test
    fun `the queue is read when a library add begins waiting and again when the wait ends`() {
        rig.client.onCommand = null
        val reads = ArrayList<Query.Queue>()
        rig.client.answerWith(ref("room"), "queue") { q ->
            reads += q as Query.Queue
            QueueResult(listOf(QueueItem("a", "A", current = true)), 1, 0)
        }

        browseWith { BrowseResult(items = emptyList()) }
        val b = browser()
        val queue = QueueControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        rig.settle()
        val before = reads.size
        b.play(item("t", kind = BrowseKind.Track), PlayMode.Add)
        rig.settle()
        assertEquals(before + 1, reads.size)
        rig.scheduler.advance(2_000)
        assertEquals(before + 2, reads.size)
        assertEquals(1, queue.state.value.total)
    }

    @Test
    fun `a play from the library is pending on the transport too, and the transport's press on the library`() {
        rig.client.onCommand = null
        browseWith { BrowseResult(items = emptyList()) }
        val b = browser()
        val transport = MediaPlayerControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        val speakers = SpeakersControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope, emptyList())
        rig.settle()
        b.play(item("t", kind = BrowseKind.Track), PlayMode.Replace)
        assertEquals(Feedback.Pending, transport.feedbackFor("replace:t"))
        assertEquals(Feedback.Pending, speakers.feedback.value["replace:t"])
        transport.press("next", "next")
        assertEquals(Feedback.Pending, b.feedback.value["next"])
        // The player changing ends them all, for everyone.
        rig.client.set(ref("room"), player(title = "Other", playback = PlaybackState.Playing, caps = noCaps.copy(browse = true, queue = true)))
        rig.settle()
        assertEquals(emptyMap<String, Feedback>(), b.feedback.value)
        assertEquals(emptyMap<String, Feedback>(), speakers.feedback.value)
    }

    @Test
    fun `another player's waits are its own`() {
        rig.client.onCommand = null
        rig.client.set(ref("a"), player("a"))
        rig.client.set(ref("b"), player("b"))
        val a = MediaPlayerControl(rig.handle(ref("a"), EntityKind.MediaPlayer), rig.scope)
        val b = MediaPlayerControl(rig.handle(ref("b"), EntityKind.MediaPlayer), rig.scope)
        a.press("next", "next")
        assertEquals(null, b.feedbackFor("next"))
    }

    @Test
    fun `a wait ends by itself after the screen that began it is gone, and calls nobody`() {
        rig.client.onCommand = null
        rig.client.set(ref("room"), player())
        val c = MediaPlayerControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        var told = 0
        rig.scope.bind(c.feedback) { told++ }
        val seen = told
        c.press("next", "next")
        rig.scope.dispose()
        rig.scheduler.advance(10_000)
        assertEquals(seen + 1, told)
        assertEquals(0, rig.scheduler.pending)
        assertEquals(null, c.feedbackFor("next"))
    }

    // --- light -------------------------------------------------------------------------------------------------

    @Test
    fun `a drag is released when the light's rounded level is the one held`() {
        rig.client.set(ref("lamp"), light(brightness = 0.2))
        val c = LightControl(rig.handle(ref("lamp"), EntityKind.Light))
        rig.settle()
        c.commit(46)
        rig.client.set(ref("lamp"), light(brightness = 0.4649))
        rig.settle()
        rig.client.set(ref("lamp"), light(brightness = 0.9))
        rig.settle()
        assertEquals(90, c.shown.value)
    }

    // --- speakers ----------------------------------------------------------------------------------------------

    @Test
    fun `the speakers' watches go with the scope`() {
        val caps = noCaps.copy(group = true)
        rig.client.set(ref("porch"), player("porch", groupable = listOf(ref("kitchen")), caps = caps))
        rig.client.set(ref("kitchen"), player("kitchen", caps = caps))
        val child = rig.scope.child()
        val s = SpeakersControl(EntityHandle(child, ref("porch"), EntityKind.MediaPlayer), child, emptyList())
        rig.settle()
        assertEquals(listOf("Kitchen"), s.speakers.value.map { it.name })
        child.dispose()
        rig.client.set(ref("kitchen"), player("kitchen").copy(name = "Renamed"))
        rig.client.set(ref("porch"), player("porch", groupable = emptyList(), caps = caps))
        rig.settle()
        assertEquals(listOf("Kitchen"), s.speakers.value.map { it.name })
        assertEquals(0, child.registered)
    }

    // --- FakeClient --------------------------------------------------------------------------------------------

    @Test
    fun `a command to an entity that does not exist, or a name its kind lacks, fails`() {
        rig.client.set(ref("plug"), switch())
        val outcomes = ArrayList<Outcome>()
        rig.client.command(ref("nobody"), "toggle") { outcomes += it }
        rig.client.command(ref("plug"), "setBrightness") { outcomes += it }
        rig.client.command(ref("plug"), "toggle") { outcomes += it }
        rig.settle()
        assertEquals(Outcome.Failed("Unknown entity: ha:nobody"), outcomes[0])
        assertEquals(Outcome.Failed("Unknown command: setBrightness"), outcomes[1])
        assertEquals(Outcome.Ok, outcomes[2])
        assertTrue(rig.client.commands.size == 3)
        rig.client.strictCommands = false
        rig.client.command(ref("nobody"), "anything") { outcomes += it }
        rig.settle()
        assertEquals(Outcome.Ok, outcomes[3])
    }
}

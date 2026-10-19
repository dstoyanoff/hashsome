package dev.hashsome.headless

import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.BrowseResult
import dev.hashsome.client.EntityKind
import dev.hashsome.client.LinkStatus
import dev.hashsome.client.PlayMode
import dev.hashsome.client.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/__tests__/media-browser*.test.tsx: the library as tabs, a stack of opened levels, a search, and playing from it.
class MediaBrowserTest {
    private val rig = Rig()
    private val asked = ArrayList<Query.Browse>()

    private val recent = item("recent", "Recently played", BrowseKind.Folder, playable = false, expandable = true)
    private val playlists = item("playlists", "Playlists", BrowseKind.Folder, playable = false, expandable = true, holds = BrowseKind.Playlist)
    private val albums = item("albums", "Albums", BrowseKind.Folder, playable = false, expandable = true, holds = BrowseKind.Album)
    private val morning = item("pl-morning", "Morning coffee", BrowseKind.Playlist, expandable = true)
    private val dinner = item("pl-dinner", "Dinner party", BrowseKind.Playlist, expandable = true)
    private val abbey = item("al-abbey", "Abbey Road", BrowseKind.Album, expandable = true)
    private val track = item("tr-1", "Come Together", BrowseKind.Track, expandable = false)

    private var library: Map<String?, BrowseResult> = mapOf(
        null to BrowseResult(items = listOf(recent, playlists, albums)),
        "recent" to BrowseResult(items = listOf(track)),
        "playlists" to BrowseResult(items = listOf(morning, dinner)),
        "albums" to BrowseResult(items = listOf(abbey)),
        "al-abbey" to BrowseResult(title = "Abbey Road", items = listOf(track)),
        "pl-morning" to BrowseResult(title = "Morning coffee", items = listOf(track)),
    )

    private var found: Map<BrowseKind?, List<BrowseItem>> = emptyMap()

    init {
        rig.client.answerWith(ref("room"), "browse") { q ->
            val browse = q as Query.Browse
            asked += browse
            if (browse.search != null) {
                BrowseResult(items = found[browse.kind] ?: emptyList())
            } else {
                library[browse.path] ?: throw IllegalStateException("No such place: ${browse.path}")
            }
        }
    }

    private fun browser(): MediaBrowserControl {
        rig.client.set(ref("room"), player(caps = noCaps.copy(browse = true, search = true)))
        return MediaBrowserControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope).also { rig.settle() }
    }

    private val MediaBrowserControl.rows get() = state.value.rows.map { it.id }
    private val MediaBrowserControl.at get() = state.value

    @Test
    fun `the top level is a row of tabs, opening on the first, and no list of folders`() {
        val b = browser()
        assertEquals(listOf("Recently played", "Playlists", "Albums"), b.at.tabs.map { it.title })
        assertEquals(listOf("recent", "playlists", "albums"), b.at.tabs.map { it.path })
        assertEquals(listOf(null, BrowseKind.Playlist, BrowseKind.Album), b.at.tabs.map { it.holds })
        assertEquals(0, b.at.active)
        assertEquals(listOf("tr-1"), b.rows)
        assertFalse(b.at.loading)
        assertNull(b.at.error)
        assertNull(b.at.search)
    }

    @Test
    fun `it is loading until the top level and then the tab answer`() {
        rig.client.set(ref("room"), player(caps = noCaps.copy(browse = true)))
        val b = MediaBrowserControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        assertTrue(b.at.loading)
        assertEquals(emptyList<String>(), b.rows)
        rig.client.queryDelayMs = 100
        rig.settle()
        // The top level is in; the first tab's list is on its way.
        assertEquals(3, b.at.tabs.size)
        assertTrue(b.at.loading)
        rig.scheduler.advance(100)
        assertFalse(b.at.loading)
    }

    @Test
    fun `the top level is read once`() {
        val b = browser()
        b.openTab(1)
        b.openTab(2)
        rig.settle()
        assertEquals(1, asked.count { it.path == null && it.search == null })
    }

    @Test
    fun `opening something changes only what is below the tabs, and the way back goes up`() {
        val b = browser()
        b.openTab(2)
        rig.settle()
        assertEquals(listOf("al-abbey"), b.rows)
        b.open(abbey)
        assertTrue(b.at.loading)
        rig.settle()
        assertEquals(listOf("tr-1"), b.rows)
        assertEquals(listOf(abbey), b.at.stack.map { it.item })
        assertEquals(listOf("Abbey Road"), b.at.stack.map { it.title })
        assertEquals(2, b.at.active)
        assertEquals(3, b.at.tabs.size)

        assertTrue(b.back())
        rig.settle()
        assertEquals(emptyList<Any>(), b.at.stack)
        assertEquals(listOf("al-abbey"), b.rows)
        assertFalse(b.back())
    }

    @Test
    fun `picking another tab from inside a shelf leaves what was open`() {
        val b = browser()
        b.openTab(1)
        rig.settle()
        b.open(morning)
        rig.settle()
        b.openTab(2)
        rig.settle()
        assertEquals(emptyList<Any>(), b.at.stack)
        assertEquals(listOf("al-abbey"), b.rows)
        assertEquals(2, b.at.active)
    }

    @Test
    fun `a tab that does not exist is ignored`() {
        val b = browser()
        b.openTab(7)
        b.openTab(-1)
        assertEquals(0, b.at.active)
    }

    @Test
    fun `a top level that is not just folders stays a plain list`() {
        library = mapOf(null to BrowseResult(items = listOf(recent, track, morning)))
        val b = browser()
        assertEquals(emptyList<Any>(), b.at.tabs)
        assertEquals(listOf("recent", "tr-1", "pl-morning"), b.rows)
        assertFalse(b.at.loading)
    }

    @Test
    fun `a single folder is not worth a tab bar`() {
        library = mapOf(null to BrowseResult(items = listOf(recent)), "recent" to BrowseResult(items = listOf(track)))
        val b = browser()
        assertEquals(emptyList<Any>(), b.at.tabs)
        assertEquals(listOf("recent"), b.rows)
    }

    @Test
    fun `a plain list can be opened into and come back`() {
        library = mapOf(null to BrowseResult(items = listOf(recent, track)), "recent" to BrowseResult(items = listOf(morning)))
        val b = browser()
        b.open(recent)
        rig.settle()
        assertEquals(listOf("pl-morning"), b.rows)
        assertTrue(b.back())
        rig.settle()
        assertEquals(listOf("recent", "tr-1"), b.rows)
        assertFalse(b.at.loading)
    }

    // --- search ------------------------------------------------------------------------------------------------

    @Test
    fun `typing waits for a pause, then asks the library for tracks`() {
        found = mapOf(BrowseKind.Track to listOf(track))
        val b = browser()
        asked.clear()
        b.search("com")
        assertTrue(b.at.loading)
        assertEquals("com", b.at.search)
        rig.scheduler.advance(299)
        assertEquals(0, asked.size)
        rig.scheduler.advance(1)
        rig.settle()
        assertEquals(listOf("com" to BrowseKind.Track), asked.map { it.search to it.kind })
        assertEquals(listOf("tr-1"), b.rows)
        assertFalse(b.at.loading)
    }

    @Test
    fun `typing on only sends the last phrase`() {
        val b = browser()
        asked.clear()
        b.search("c")
        rig.scheduler.advance(200)
        b.search("co")
        rig.scheduler.advance(200)
        b.search("com")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(listOf("com"), asked.map { it.search })
    }

    @Test
    fun `results are ranked, an exact title first`() {
        found = mapOf(
            BrowseKind.Track to listOf(
                item("1", "Chill Music Mix", BrowseKind.Track),
                item("2", "Music", BrowseKind.Track),
                item("3", "Music Radio", BrowseKind.Track),
            ),
        )

        val b = browser()
        b.search("music")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(listOf("2", "3", "1"), b.rows)
    }

    @Test
    fun `a shelf that holds one kind searches for that kind`() {
        found = mapOf(BrowseKind.Playlist to listOf(morning), BrowseKind.Track to listOf(track))
        val b = browser()
        b.openTab(1)
        rig.settle()
        asked.clear()
        b.search("morn")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(BrowseKind.Playlist, asked.single().kind)
        assertEquals(listOf("pl-morning"), b.rows)
    }

    @Test
    fun `clearing the search returns to where the browser was`() {
        found = mapOf(BrowseKind.Track to listOf(track))
        val b = browser()
        b.openTab(1)
        rig.settle()
        b.open(morning)
        rig.settle()
        b.search("come")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals("come", b.at.search)

        b.search("")
        rig.settle()
        assertNull(b.at.search)
        assertEquals(listOf(morning), b.at.stack.map { it.item })
        assertEquals(1, b.at.active)
        assertEquals(listOf("tr-1"), b.rows)
        b.search(null)
        assertNull(b.at.search)
        b.search("   ")
        assertNull(b.at.search)
    }

    @Test
    fun `back clears the search before going up a level`() {
        val b = browser()
        b.openTab(1)
        rig.settle()
        b.open(morning)
        rig.settle()
        b.search("x")
        assertTrue(b.back())
        assertNull(b.at.search)
        assertEquals(1, b.at.stack.size)
        assertTrue(b.back())
        assertEquals(0, b.at.stack.size)
    }

    @Test
    fun `opening an album from search results opens it, and going back returns to the results`() {
        found = mapOf(BrowseKind.Album to listOf(abbey), BrowseKind.Track to listOf(track))
        val b = browser()
        b.openTab(2)
        rig.settle()
        b.search("abbey")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(listOf("al-abbey"), b.rows)

        b.open(abbey)
        // The search steps aside.
        assertNull(b.at.search)
        rig.settle()
        assertEquals(listOf("tr-1"), b.rows)

        assertTrue(b.back())
        assertEquals("abbey", b.at.search)
        assertEquals(emptyList<Any>(), b.at.stack)
        // The results are asked for again, after the pause a search has.
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(listOf("al-abbey"), b.rows)
    }

    @Test
    fun `going back to the results asks for them again, after the pause`() {
        found = mapOf(BrowseKind.Track to listOf(track))
        val b = browser()
        b.openTab(1)
        rig.settle()
        b.open(morning)
        rig.settle()
        b.search("come")
        rig.scheduler.advance(300)
        rig.settle()
        b.open(track.copy(expandable = true))
        asked.clear()
        b.back()
        assertEquals("come", b.at.search)
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(listOf("come"), asked.map { it.search })
    }

    // --- errors, reconnects --------------------------------------------------------------------------------------

    @Test
    fun `a library that cannot be read shows the reason`() {
        rig.client.failQuery("Not connected to runtime")
        val b = browser()
        assertEquals("Not connected to runtime", b.at.error)
        assertFalse(b.at.loading)
        assertEquals(emptyList<String>(), b.rows)
    }

    @Test
    fun `a level that fails shows its error and the others still work`() {
        val b = browser()
        b.openTab(1)
        rig.settle()
        b.open(item("nowhere", "Nowhere", BrowseKind.Folder, expandable = true))
        rig.settle()
        assertEquals("No such place: nowhere", b.at.error)
        assertTrue(b.back())
        rig.settle()
        assertNull(b.at.error)
        assertEquals(listOf("pl-morning", "pl-dinner"), b.rows)
    }

    @Test
    fun `the library asks again when the connection is back, instead of staying on its error`() {
        rig.client.link.set(LinkStatus.Closed)
        rig.client.failQuery("Not connected to runtime")
        val b = browser()
        assertEquals("Not connected to runtime", b.at.error)
        assertEquals(emptyList<Any>(), b.at.tabs)

        rig.client.failQuery(null)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertNull(b.at.error)
        assertEquals(3, b.at.tabs.size)
        assertEquals(listOf("tr-1"), b.rows)
    }

    @Test
    fun `nothing is delivered after the scope is disposed`() {
        rig.client.set(ref("room"), player(caps = noCaps.copy(browse = true)))
        val b = MediaBrowserControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        rig.scope.dispose()
        rig.settle()
        assertTrue(b.at.loading)
        b.search("x")
        rig.scheduler.advance(1_000)
        assertEquals(0, rig.scheduler.pending)
    }

    // --- the arrival of rows -------------------------------------------------------------------------------------

    @Test
    fun `a long list opens a row at a time`() {
        library = library + ("playlists" to BrowseResult(items = (1..40).map { item("p$it", "Playlist $it", BrowseKind.Playlist, expandable = true) }))
        val b = browser()
        b.openTab(1)
        rig.settle()
        assertEquals(40, b.rows.size)
        assertEquals(1, b.arrival.visible.value)
        rig.scheduler.advance(55 * 9)
        assertEquals(10, b.arrival.visible.value)
    }

    @Test
    fun `a short list is shown whole, and the same list answering again does not start over`() {
        val b = browser()
        b.openTab(1)
        rig.settle()
        assertEquals(2, b.arrival.visible.value)
        rig.client.link.set(LinkStatus.Closed)
        rig.client.link.set(LinkStatus.Open)
        rig.settle()
        assertEquals(2, b.arrival.visible.value)
    }

    // --- playing -------------------------------------------------------------------------------------------------

    @Test
    fun `a track on a shelf plays on its own, with no album around it`() {
        val b = browser()
        b.play(track, PlayMode.Replace)
        val sent = rig.client.commands.last()
        assertEquals("playMedia", sent.second)
        assertEquals("tr-1", sent.third!!.getString("item"))
        assertEquals("replace", sent.third!!.getString("mode"))
        assertFalse(sent.third!!.has("context"))
    }

    @Test
    fun `a track in an album plays the whole album from there`() {
        val b = browser()
        b.openTab(2)
        rig.settle()
        b.open(abbey)
        rig.settle()
        b.play(track, PlayMode.Replace)
        assertEquals("al-abbey", rig.client.commands.last().third!!.getString("context"))
        // Adding to the queue is the track alone.
        b.play(track, PlayMode.Add)
        assertFalse(rig.client.commands.last().third!!.has("context"))
        assertEquals("add", rig.client.commands.last().third!!.getString("mode"))
        // So is playing an album from inside a playlist: only a track plays in its container.
        b.play(abbey, PlayMode.Replace)
        assertFalse(rig.client.commands.last().third!!.has("context"))
    }

    @Test
    fun `the default is play`() {
        val b = browser()
        b.play(track)
        assertEquals("play", rig.client.commands.last().third!!.getString("mode"))
    }

    @Test
    fun `an item asked to play is waiting until the player answers, and a failure shows red for a moment`() {
        rig.client.onCommand = null
        val b = browser()
        b.play(track, PlayMode.Replace)
        assertEquals(mapOf<String, Feedback>("replace:tr-1" to Feedback.Pending), b.feedback.value)
        rig.scheduler.advance(14_999)
        assertEquals(Feedback.Pending, b.feedback.value["replace:tr-1"])
        rig.scheduler.advance(1)
        assertEquals(emptyMap<String, Feedback>(), b.feedback.value)

        rig.client.failNext("player is busy")
        b.play(track, PlayMode.Next)
        rig.settle()
        assertEquals(Feedback.Error, b.feedback.value["next:tr-1"])
        rig.scheduler.advance(700)
        assertEquals(emptyMap<String, Feedback>(), b.feedback.value)
    }

    // --- the kind of a search, and shuffling -----------------------------------------------------------------------

    @Test
    fun `the kinds to search for are offered while searching, starting on the shelf's`() {
        val b = browser()
        assertEquals(emptyList<BrowseKind>(), b.at.kinds)
        b.openTab(2)
        rig.settle()
        b.search("a")
        assertEquals(listOf(BrowseKind.Track, BrowseKind.Playlist, BrowseKind.Album, BrowseKind.Artist), b.at.kinds)
        assertEquals(BrowseKind.Album, b.at.kind)
    }

    @Test
    fun `choosing a kind asks for that kind, keeps it while typing, and forgets it when cleared`() {
        found = mapOf(BrowseKind.Artist to listOf(item("ar", "Abba", BrowseKind.Artist)), BrowseKind.Track to listOf(track))
        val b = browser()
        b.search("ab", BrowseKind.Artist)
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(BrowseKind.Artist, asked.last().kind)
        assertEquals(listOf("ar"), b.rows)
        b.search("abb")
        rig.scheduler.advance(300)
        rig.settle()
        assertEquals(BrowseKind.Artist, asked.last().kind)
        b.search("")
        b.search("abb")
        assertEquals(BrowseKind.Track, b.at.kind)
        // A kind that is not offered is ignored.
        b.search("abb", BrowseKind.Radio)
        assertEquals(BrowseKind.Track, b.at.kind)
    }

    @Test
    fun `opening a result with a kind chosen, and going back, returns to that kind`() {
        found = mapOf(BrowseKind.Playlist to listOf(morning))
        val b = browser()
        b.search("morn", BrowseKind.Playlist)
        rig.scheduler.advance(300)
        rig.settle()
        b.open(morning)
        rig.settle()
        assertTrue(b.back())
        assertEquals("morn", b.at.search)
        assertEquals(BrowseKind.Playlist, b.at.kind)
    }

    @Test
    fun `an album can be played shuffled`() {
        val b = browser()
        b.play(abbey, PlayMode.Replace, shuffle = true)
        assertEquals(true, rig.client.commands.last().third!!.getBoolean("shuffle"))
        b.play(abbey, PlayMode.Replace)
        assertFalse(rig.client.commands.last().third!!.has("shuffle"))
    }
}

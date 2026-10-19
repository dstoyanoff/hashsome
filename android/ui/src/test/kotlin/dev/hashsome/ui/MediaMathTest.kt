package dev.hashsome.ui

import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaGroup
import dev.hashsome.client.MediaSource
import dev.hashsome.client.SourceKind
import dev.hashsome.headless.Feedback
import dev.hashsome.headless.Speaker
import dev.hashsome.headless.SpeakerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMathTest {
    private fun track(duration: Double?, id: String = "t") = BrowseItem(id, id, null, null, BrowseKind.Track, duration, playable = true, expandable = false)

    @Test
    fun `a position is minutes and seconds, with hours once there are some`() {
        assertEquals("0:00", MediaFormat.duration(0.0))
        assertEquals("1:05", MediaFormat.duration(65.9))
        assertEquals("1:02:03", MediaFormat.duration(3723.0))
        assertEquals("0:00", MediaFormat.duration(-5.0))
    }

    @Test
    fun `a length is said plainly`() {
        assertEquals("1 min", MediaFormat.length(5.0))
        assertEquals("42 min", MediaFormat.length(42 * 60.0))
        assertEquals("2 h", MediaFormat.length(2 * 3600.0))
        assertEquals("7 h 33 min", MediaFormat.length(7 * 3600.0 + 33 * 60))
    }

    @Test
    fun `what a queue was filled from is said with its kind`() {
        assertEquals("Playlist · Daily Mix 2", MediaFormat.source(MediaSource("p", "Daily Mix 2", SourceKind.Playlist)))
        assertEquals("Radio station · Jazz FM", MediaFormat.source(MediaSource("r", "Jazz FM", SourceKind.Radio)))
        assertEquals("Mix", MediaFormat.source(MediaSource("m", "Mix", null)))
    }

    @Test
    fun `what is in an opened list is counted in its own nouns, with a length only when every track has one`() {
        assertEquals("1 song · 4 min", MediaFormat.summary(listOf(track(240.0))))
        assertEquals("2 songs · 8 min", MediaFormat.summary(listOf(track(240.0), track(240.0))))
        assertEquals("2 songs", MediaFormat.summary(listOf(track(240.0), track(null))))
        val album = BrowseItem("a", "a", null, null, BrowseKind.Album, null, playable = true, expandable = true)
        assertEquals("3 albums", MediaFormat.summary(listOf(album, album, album)))
        assertEquals("2 items", MediaFormat.summary(listOf(album, track(1.0))))
    }

    @Test
    fun `the queue's heading counts its tracks and says how long they play`() {
        assertEquals(" · 1 track · 4 min", MediaFormat.queueCount(1, 240.0))
        assertEquals(" · 12 tracks", MediaFormat.queueCount(12, null))
        assertEquals(" · 0 tracks", MediaFormat.queueCount(0, 100.0))
    }

    @Test
    fun `the speakers pill says how they stand`() {
        assertEquals("Speakers", MediaFormat.speakers(0, emptyList()))
        assertEquals("3 speakers", MediaFormat.speakers(3, listOf("Patio")))
        assertEquals("Patio playing", MediaFormat.speakers(0, listOf("Patio")))
        assertEquals("2 streams playing", MediaFormat.speakers(0, listOf("Patio", "Porch")))
    }

    @Test
    fun `the speakers summary counts a group, or one stream per leader`() {
        val me = EntityRef("ma:me")
        val kitchen = EntityRef("ma:kitchen")
        val office = EntityRef("ma:office")
        val group = MediaGroup(leader = me, members = listOf(kitchen))
        assertEquals(2, SpeakersSummary.of(group, emptyList(), emptyMap()).together)

        val speakers = listOf(Speaker(kitchen, "Kitchen", false), Speaker(office, "Office", false))
        // Both play one stream, led by the kitchen: one stream, named by who leads it.
        val status = mapOf(
            kitchen to SpeakerStatus(true, "Dreams", null, true),
            office to SpeakerStatus(true, "Dreams", kitchen, true),
        )
        val summary = SpeakersSummary.of(null, speakers, status)
        assertEquals(0, summary.together)
        assertEquals(listOf("Kitchen"), summary.elsewhere)
        assertEquals("Kitchen playing", summary.label)
        // Quiet speakers say nothing.
        assertEquals("Speakers", SpeakersSummary.of(null, speakers, mapOf(kitchen to SpeakerStatus(true, null, null, false))).label)
    }

    @Test
    fun `dragging round the ring starts where it was pressed and follows how far the finger goes`() {
        val seek = RingSeek(200.0)
        // Pressed at a quarter turn: 50 s.
        assertEquals(50.0, seek.start(0.25), 0.0)
        assertEquals(100.0, seek.follow(0.5), 0.0)
        assertEquals(150.0, seek.follow(0.75), 0.0)
    }

    @Test
    fun `carrying on past the top holds the end instead of jumping to the start`() {
        val seek = RingSeek(100.0)
        seek.start(0.9)
        assertEquals(98.0, seek.follow(0.98), 1e-9)
        // Over the top: 0.02 is a short step on from 0.98, so the position stays at the end.
        assertEquals(100.0, seek.follow(0.02), 0.0)
        assertEquals(100.0, seek.follow(0.1), 0.0)
        // Back round the way it came, over the top again, it moves again.
        assertEquals(99.0, seek.follow(0.99), 1e-9)
        assertEquals(90.0, seek.follow(0.9), 1e-9)
    }

    @Test
    fun `the turn of a point is clockwise from the top`() {
        assertEquals(0.0, RingSeek.turnAt(0.0, -10.0), 1e-9)
        assertEquals(0.25, RingSeek.turnAt(10.0, 0.0), 1e-9)
        assertEquals(0.5, RingSeek.turnAt(0.0, 10.0), 1e-9)
        assertEquals(0.75, RingSeek.turnAt(-10.0, 0.0), 1e-9)
    }

    @Test
    fun `only the ring seeks, not the picture inside it`() {
        assertFalse(RingSeek.onRing(30.0, 100.0))
        assertTrue(RingSeek.onRing(40.0, 100.0))
        assertTrue(RingSeek.onRing(49.0, 100.0))
    }

    @Test
    fun `a row opens what opens and otherwise plays, and the item playing carries the toggle`() {
        assertEquals(RowMain.Open, BrowseActions.main(expandable = true, playable = true, active = false))
        assertEquals(RowMain.Play, BrowseActions.main(expandable = false, playable = true, active = false))
        assertEquals(RowMain.Toggle, BrowseActions.main(expandable = false, playable = true, active = true))
        assertEquals(RowMain.None, BrowseActions.main(expandable = false, playable = false, active = false))
        assertEquals(RowMain.Open, BrowseActions.main(expandable = true, playable = false, active = false))
    }

    @Test
    fun `a row has the buttons the item and the player allow`() {
        // A track on a player with a queue: play next and add.
        assertEquals(listOf(RowButton.Next, RowButton.Add), BrowseActions.buttons(false, true, true, false))
        // An album on one: next, add and play.
        assertEquals(listOf(RowButton.Next, RowButton.Add, RowButton.Play), BrowseActions.buttons(true, true, true, false))
        // On a player with no queue it can only play.
        assertEquals(listOf(RowButton.Play), BrowseActions.buttons(true, true, false, false))
        assertEquals(emptyList<RowButton>(), BrowseActions.buttons(false, true, false, false))
        // A folder has nothing.
        assertEquals(emptyList<RowButton>(), BrowseActions.buttons(true, false, true, false))
    }

    @Test
    fun `the active item keeps the room its other buttons would take, empty`() {
        assertEquals(listOf(RowButton.Spare, RowButton.Toggle), BrowseActions.buttons(false, true, true, true))
        assertEquals(listOf(RowButton.Spare, RowButton.Spare, RowButton.Toggle), BrowseActions.buttons(true, true, true, true))
        assertEquals(listOf(RowButton.Toggle), BrowseActions.buttons(true, true, false, true))
    }

    @Test
    fun `a row's buttons sit at its right edge and the body is what is left`() {
        val none = RowGeometry(400f, 44f, 8f, 0)
        assertEquals(400f, none.mainWidth, 0f)
        assertEquals(0, none.regionAt(399f))

        val two = RowGeometry(400f, 44f, 8f, 2)
        assertEquals(296f, two.mainWidth, 0f)
        assertEquals(304f, two.buttonLeft(0), 0f)
        assertEquals(356f, two.buttonLeft(1), 0f)
        assertEquals(0, two.regionAt(100f))
        assertEquals(-1, two.regionAt(300f))
        assertEquals(1, two.regionAt(320f))
        assertEquals(-1, two.regionAt(351f))
        assertEquals(2, two.regionAt(399f))
    }

    @Test
    fun `a queue row's handle and cross are at its edges`() {
        val geo = QueueGeometry(400f, 24f, 32f)
        assertTrue(geo.onGrip(10f))
        assertFalse(geo.onGrip(30f))
        assertTrue(geo.onRemove(380f))
        assertFalse(geo.onRemove(300f))
    }

    @Test
    fun `a dragged row is over the place its distance says`() {
        assertEquals(2, QueueDrag.target(from = 2, offset = 10f, pitch = 58f, last = 5))
        assertEquals(3, QueueDrag.target(from = 2, offset = 30f, pitch = 58f, last = 5))
        assertEquals(0, QueueDrag.target(from = 2, offset = -400f, pitch = 58f, last = 5))
        assertEquals(5, QueueDrag.target(from = 2, offset = 400f, pitch = 58f, last = 5))
        assertEquals(0, QueueDrag.target(from = 0, offset = 100f, pitch = 58f, last = -1))
    }

    @Test
    fun `the rows a dragged one passes give way by a place`() {
        // From 1 down to 3: rows 2 and 3 move up.
        assertEquals(0, QueueDrag.shift(0, 1, 3))
        assertEquals(0, QueueDrag.shift(1, 1, 3))
        assertEquals(-1, QueueDrag.shift(2, 1, 3))
        assertEquals(-1, QueueDrag.shift(3, 1, 3))
        assertEquals(0, QueueDrag.shift(4, 1, 3))
        // From 3 up to 1: rows 1 and 2 move down.
        assertEquals(0, QueueDrag.shift(0, 3, 1))
        assertEquals(1, QueueDrag.shift(1, 3, 1))
        assertEquals(1, QueueDrag.shift(2, 3, 1))
        assertEquals(0, QueueDrag.shift(3, 3, 1))
    }

    @Test
    fun `a track dropped somewhere is in that place`() {
        assertEquals(listOf("a", "c", "d", "b", "e"), QueueDrag.reorder(listOf("a", "b", "c", "d", "e"), 1, 3))
        assertEquals(listOf("c", "a", "b"), QueueDrag.reorder(listOf("a", "b", "c"), 2, 0))
        assertEquals(listOf("a", "b"), QueueDrag.reorder(listOf("a", "b"), 1, 1))
    }

    @Test
    fun `a swipe back is far enough to the right and clearly sideways`() {
        assertTrue(SwipeBack.counts(100f, 10f))
        assertFalse(SwipeBack.counts(72f, 0f))
        assertFalse(SwipeBack.counts(100f, 60f))
        assertFalse(SwipeBack.counts(-100f, 0f))
    }

    @Test
    fun `the title of what is being started is remembered for the bar that waits for it`() {
        val ref = EntityRef("ma:kitchen")
        PlayRequests.note(ref, "album-1", "1989")
        assertEquals("1989", PlayRequests.loading(ref, mapOf("replace:album-1" to Feedback.Pending)))
        // Another player's, or one that was not noted, is a wait with no name.
        assertEquals("", PlayRequests.loading(ref, mapOf("play:unknown" to Feedback.Pending)))
        // Adding to the queue is not a player loading.
        assertNull(PlayRequests.loading(ref, mapOf("add:album-1" to Feedback.Pending)))
        assertNull(PlayRequests.loading(ref, mapOf("replace:album-1" to Feedback.Error)))
        assertNull(PlayRequests.loading(ref, emptyMap()))
    }
}

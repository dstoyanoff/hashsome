package dev.hashsome.headless

import dev.hashsome.client.EntityKind
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/media-progress.ts and entities/media-seek.ts.
class MediaProgressTest {
    private val rig = Rig()
    private val noon = "2026-06-01T12:00:00Z"

    init {
        rig.scheduler.wall = 1_780_315_200_000 // the noon above
        rig.client.onCommand = null
    }

    private fun control(p: MediaPlayerEntity): MediaPlayerControl {
        rig.client.set(ref("room"), p)
        return MediaPlayerControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope).also { rig.settle() }
    }

    private fun playing(position: Double? = 10.0, duration: Double? = 100.0, at: String? = noon, title: String? = "One") =
        player(playback = PlaybackState.Playing, title = title, position = position, duration = duration, positionUpdatedAt = at)

    // --- position ----------------------------------------------------------------------------------------------

    @Test
    fun `a playing player advances from its reported position on its own clock`() {
        val c = control(playing())
        assertEquals(10.0, c.position()!!, 0.0)
        rig.scheduler.advance(5_000)
        assertEquals(15.0, c.position()!!, 0.01)
        assertEquals(0.15, c.fraction()!!, 0.0001)
    }

    @Test
    fun `a paused player stays where it was`() {
        val c = control(player(playback = PlaybackState.Paused, position = 10.0, duration = 100.0, positionUpdatedAt = "2026-06-01T11:00:00Z"))
        assertEquals(10.0, c.position()!!, 0.0)
        rig.scheduler.advance(60_000)
        assertEquals(10.0, c.position()!!, 0.0)
    }

    @Test
    fun `a long-playing one stops at the end`() {
        val c = control(playing(at = "2026-06-01T11:00:00Z"))
        assertEquals(100.0, c.position()!!, 0.0)
        assertEquals(1.0, c.fraction()!!, 0.0)
    }

    @Test
    fun `none reports nothing`() {
        val c = control(playing(position = null))
        assertNull(c.position())
        assertNull(c.fraction())
        assertNull(c.shownPosition())
    }

    @Test
    fun `a stream with no end has a position and no fraction`() {
        val c = control(playing(duration = null))
        assertEquals(10.0, c.position()!!, 0.0)
        assertNull(c.fraction())
        assertNull(control(playing(duration = 0.0)).fraction())
    }

    @Test
    fun `a report with no time, or a time in the future, does not run backwards`() {
        assertEquals(10.0, control(playing(at = null)).position()!!, 0.0)
        assertEquals(10.0, control(playing(at = "2026-06-01T12:00:30Z")).position()!!, 0.0)
        assertEquals(10.0, control(playing(at = "not a time")).position()!!, 0.0)
    }

    @Test
    fun `no entity, no position`() {
        val c = MediaPlayerControl(rig.handle(ref("ghost"), EntityKind.MediaPlayer), rig.scope)
        rig.settle()
        assertNull(c.position())
    }

    // --- changed -----------------------------------------------------------------------------------------------

    @Test
    fun `changed ticks every half second while the player is playing`() {
        val c = control(playing())
        val seen = ArrayList<Long>()
        c.changed.listen { seen += it }
        rig.scheduler.advance(1_700)
        assertEquals(listOf(500L, 1_000L, 1_500L), seen)
        assertEquals(500L, MediaPlayerControl.TICK_MS)
    }

    @Test
    fun `the ticking stops when the player pauses and starts again when it plays`() {
        val c = control(playing())
        var ticks = 0
        c.changed.listen { ticks++ }
        rig.scheduler.advance(1_000)
        assertEquals(2, ticks)
        rig.client.set(ref("room"), player(playback = PlaybackState.Paused, position = 12.0, duration = 100.0, positionUpdatedAt = noon))
        rig.settle()
        rig.scheduler.advance(5_000)
        assertEquals(2, ticks)
        rig.client.set(ref("room"), playing())
        rig.settle()
        rig.scheduler.advance(500)
        assertEquals(3, ticks)
    }

    @Test
    fun `a paused player never ticks, and the ticking ends with the scope`() {
        val c = control(player(playback = PlaybackState.Paused))
        var ticks = 0
        c.changed.listen { ticks++ }
        rig.scheduler.advance(5_000)
        assertEquals(0, ticks)
        rig.client.set(ref("room"), playing())
        rig.settle()
        rig.scheduler.advance(500)
        assertEquals(1, ticks)
        rig.scope.dispose()
        rig.scheduler.advance(5_000)
        assertEquals(1, ticks)
    }

    // --- steady playing ----------------------------------------------------------------------------------------

    private fun state(playback: PlaybackState, title: String) =
        player(playback = playback, title = title, position = 0.0, positionUpdatedAt = noon)

    @Test
    fun `a dip out of playing just after a track change is not shown, but a lasting one is`() {
        val c = control(state(PlaybackState.Playing, "One"))
        assertTrue(c.playing.value)
        rig.client.set(ref("room"), state(PlaybackState.Playing, "Two"))
        rig.client.set(ref("room"), state(PlaybackState.Idle, "Two"))
        rig.settle()
        assertTrue(c.playing.value)
        rig.client.set(ref("room"), state(PlaybackState.Playing, "Two"))
        rig.settle()
        rig.scheduler.advance(2_000)
        assertTrue(c.playing.value)

        // The same dip that lasts is believed.
        rig.client.set(ref("room"), state(PlaybackState.Idle, "Two"))
        rig.settle()
        assertTrue(c.playing.value)
        rig.scheduler.advance(799)
        assertTrue(c.playing.value)
        rig.scheduler.advance(1)
        assertEquals(false, c.playing.value)
    }

    @Test
    fun `a pause long after a track started shows at once`() {
        val c = control(state(PlaybackState.Playing, "One"))
        rig.client.set(ref("room"), state(PlaybackState.Playing, "Two"))
        rig.settle()
        rig.scheduler.advance(10_000)
        rig.client.set(ref("room"), state(PlaybackState.Idle, "Two"))
        rig.settle()
        assertEquals(false, c.playing.value)
    }

    @Test
    fun `a pause with no new track shows at once`() {
        val c = control(state(PlaybackState.Playing, "One"))
        // The first track after loading opens the window too, as on the web: let it close.
        rig.scheduler.advance(7_000)
        rig.client.set(ref("room"), state(PlaybackState.Paused, "One"))
        rig.settle()
        assertEquals(false, c.playing.value)
    }

    // --- seek --------------------------------------------------------------------------------------------------

    @Test
    fun `the seek bar follows the position while nobody drags it`() {
        val c = control(playing())
        assertEquals(10.0, c.seek.shown.value!!, 0.0)
        rig.scheduler.advance(2_000)
        assertEquals(12.0, c.seek.shown.value!!, 0.01)
        assertEquals(c.seek.shown.value, c.shownPosition())
    }

    @Test
    fun `nothing playing, nothing on the bar`() {
        assertNull(control(player()).seek.shown.value)
    }

    @Test
    fun `dragging shows the spot and sends nothing`() {
        val c = control(playing())
        c.seek.drag(60.0)
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
        rig.scheduler.advance(3_000)
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
        assertEquals(0, rig.client.commands.size)
    }

    @Test
    fun `after a seek the new position is held until the player reports one that moved`() {
        val c = control(playing())
        c.seek.commit(60.0)
        assertEquals("seek", rig.client.commands[0].second)
        assertEquals(60.0, rig.client.commands[0].third!!.getDouble("position"), 0.0)
        rig.settle()
        rig.scheduler.advance(2_000)
        // The player has said nothing yet: the bar does not snap back to where it was.
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
        // It reports the same position again: still not an answer.
        rig.client.set(ref("room"), playing().copy(lastChanged = "x"))
        rig.settle()
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
        // It reports where it is now: the bar follows the player again.
        rig.client.set(ref("room"), playing(position = 60.0, at = "2026-06-01T12:00:02Z"))
        rig.settle()
        assertEquals(60.0, c.seek.shown.value!!, 0.01)
        rig.scheduler.advance(1_000)
        assertEquals(61.0, c.seek.shown.value!!, 0.01)
    }

    @Test
    fun `a periodic report of the same position, stamped later, is not the answer to a seek`() {
        val c = control(playing())
        c.seek.commit(60.0)
        rig.scheduler.advance(2_000)
        rig.client.set(ref("room"), playing(at = "2026-06-01T12:00:02Z"))
        rig.settle()
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
    }

    @Test
    fun `the first track after loading opens the window in which a dip is held`() {
        rig.client.set(ref("room"), state(PlaybackState.Playing, "One"))
        val c = MediaPlayerControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope)
        rig.settle()
        rig.client.set(ref("room"), state(PlaybackState.Idle, "One"))
        rig.settle()
        assertTrue(c.playing.value)
        rig.scheduler.advance(800)
        assertEquals(false, c.playing.value)
    }

    @Test
    fun `a seek the player never answers is given up after 8 seconds`() {
        val c = control(playing())
        c.seek.commit(60.0)
        rig.scheduler.advance(7_999)
        assertEquals(60.0, c.seek.shown.value!!, 0.0)
        rig.scheduler.advance(1)
        assertEquals(18.0, c.seek.shown.value!!, 0.01)
        assertEquals(8_000L, SeekControl.IDLE_MS)
    }

    @Test
    fun `a drag whose end never came lets the position go after 8 seconds, and each move restarts the wait`() {
        val c = control(playing())
        c.seek.drag(30.0)
        rig.scheduler.advance(5_000)
        c.seek.drag(40.0)
        rig.scheduler.advance(5_000)
        assertEquals(40.0, c.seek.shown.value!!, 0.0)
        rig.scheduler.advance(3_000)
        assertEquals(23.0, c.seek.shown.value!!, 0.01)
    }

    @Test
    fun `a cancelled drag goes back to the position with no seek`() {
        val c = control(playing())
        c.seek.drag(30.0)
        c.seek.cancel()
        assertEquals(10.0, c.seek.shown.value!!, 0.0)
        assertEquals(0, rig.client.commands.size)
        rig.scheduler.advance(20_000)
        assertEquals(30.0, c.seek.shown.value!!, 0.01)
    }

    // --- formatting --------------------------------------------------------------------------------------------

    @Test
    fun `durations read m_ss, and h_mm_ss from an hour`() {
        assertEquals("0:00", formatDuration(0.0))
        assertEquals("1:04", formatDuration(64.9))
        assertEquals("1:02:05", formatDuration(3_725.0))
        assertEquals("0:00", formatDuration(-3.0))
    }

    @Test
    fun `a length is said plainly`() {
        assertEquals("1 min", formatLength(10.0))
        assertEquals("42 min", formatLength(42 * 60.0))
        assertEquals("2 h", formatLength(2 * 3600.0))
        assertEquals("7 h 33 min", formatLength(7 * 3600.0 + 33 * 60))
        assertEquals("1 h", formatLength(59 * 60.0 + 40))
    }

    @Test
    fun `timestamps read with a zone or fractions`() {
        assertEquals(1_780_315_200_000, IsoTime.parse("2026-06-01T12:00:00Z"))
        assertEquals(1_780_315_200_250, IsoTime.parse("2026-06-01T12:00:00.25Z"))
        assertEquals(1_780_315_200_123, IsoTime.parse("2026-06-01T12:00:00.123456Z"))
        assertEquals(1_780_315_200_000, IsoTime.parse("2026-06-01T14:00:00+02:00"))
        assertEquals(1_780_315_200_000, IsoTime.parse("2026-06-01T07:30:00-0430"))
        assertEquals(1_780_315_200_000, IsoTime.parse("2026-06-01T12:00:00"))
        assertEquals(0L, IsoTime.parse("1970-01-01T00:00:00.000Z"))
        assertEquals(-1_000L, IsoTime.parse("1969-12-31T23:59:59Z"))
        assertNull(IsoTime.parse("yesterday"))
        assertNull(IsoTime.parse(null))
        assertNull(IsoTime.parse("2026-13-01T00:00:00Z"))
    }
}

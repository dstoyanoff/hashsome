package dev.hashsome.headless

import dev.hashsome.client.EntityKind
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.QueueResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/media-controls.ts: useVolumeControl, usePressFeedback, transportAvailability.
class MediaControlsTest {
    private val rig = Rig()
    private val names get() = rig.client.commands.map { it.second }

    private fun control(p: MediaPlayerEntity = player(), applyCommands: Boolean = false): MediaPlayerControl {
        if (!applyCommands) rig.client.onCommand = null
        rig.client.set(ref("room"), p)
        return MediaPlayerControl(rig.handle(ref("room"), EntityKind.MediaPlayer), rig.scope).also { rig.settle() }
    }

    // --- volume ------------------------------------------------------------------------------------------------

    @Test
    fun `the volume is the player's, the slider shows it`() {
        val c = control(player(volume = 0.4, muted = true))
        assertEquals(0.4, c.volume.level.value!!, 0.0)
        assertEquals(0.4, c.volume.shown.value, 0.0)
        assertTrue(c.volume.muted.value)
    }

    @Test
    fun `a player with no volume has none, and the slider shows zero`() {
        val c = control(player(volume = null))
        assertNull(c.volume.level.value)
        assertEquals(0.0, c.volume.shown.value, 0.0)
    }

    @Test
    fun `a control that is let go of stops following the player`() {
        rig.client.set(ref("room"), player(volume = 0.4))
        val own = rig.scope.child()
        val c = MediaPlayerControl(EntityHandle(own, ref("room"), EntityKind.MediaPlayer), own).also { rig.settle() }
        assertEquals(0.4, c.volume.level.value!!, 0.0)
        own.dispose()
        rig.client.set(ref("room"), player(volume = 0.9))
        rig.settle()
        assertEquals(0.4, c.volume.level.value!!, 0.0)
    }

    @Test
    fun `dragging shows the spot and sends nothing`() {
        val c = control()
        c.volume.drag(0.8)
        assertEquals(0.8, c.volume.shown.value, 0.0)
        assertEquals(emptyList<String>(), names)
    }

    @Test
    fun `committing sends the level and the slider stays until the player reports it`() {
        val c = control(player(volume = 0.4))
        c.volume.commit(0.7)
        assertEquals(listOf("setVolume"), names)
        assertEquals(0.7, rig.client.commands[0].third!!.getDouble("volume"), 0.0)
        assertEquals(0.7, c.volume.shown.value, 0.0)
        rig.client.set(ref("room"), player(volume = 0.7))
        rig.settle()
        assertEquals(0.7, c.volume.shown.value, 0.0)
        // Let go: a change from elsewhere shows.
        rig.client.set(ref("room"), player(volume = 0.2))
        rig.settle()
        assertEquals(0.2, c.volume.shown.value, 0.0)
    }

    @Test
    fun `a level the player rounds to the same percent releases the slider`() {
        val c = control(player(volume = 0.4))
        c.volume.commit(0.456)
        rig.client.set(ref("room"), player(volume = 0.46))
        rig.settle()
        rig.client.set(ref("room"), player(volume = 0.1))
        rig.settle()
        assertEquals(0.1, c.volume.shown.value, 0.0)
    }

    @Test
    fun `committing zero mutes, and committing above zero unmutes a muted player`() {
        val loud = control(player(volume = 0.4, muted = false))
        loud.volume.commit(0.0)
        assertEquals(listOf("setVolume", "setMuted"), names)
        assertEquals(true, rig.client.commands[1].third!!.getBoolean("muted"))

        rig.client.commands.clear()
        rig.client.set(ref("room"), player(volume = 0.0, muted = true))
        rig.settle()
        loud.volume.commit(0.3)
        assertEquals(listOf("setVolume", "setMuted"), names)
        assertEquals(false, rig.client.commands[1].third!!.getBoolean("muted"))
    }

    @Test
    fun `committing to an audible level on a player that is not muted only sets the level`() {
        control(player(volume = 0.4)).volume.commit(0.9)
        assertEquals(listOf("setVolume"), names)
    }

    @Test
    fun `unmuting a player whose level is zero sets the level too, instead of staying silent`() {
        val c = control(player(volume = 0.0, muted = true))
        c.volume.toggleMute()
        assertEquals(listOf("setVolume", "setMuted"), names)
        assertEquals(VolumeControl.UNMUTE_FROM_ZERO, rig.client.commands[0].third!!.getDouble("volume"), 0.0)
        assertEquals(0.15, VolumeControl.UNMUTE_FROM_ZERO, 0.0)
        assertEquals(false, rig.client.commands[1].third!!.getBoolean("muted"))
    }

    @Test
    fun `muting and unmuting at a level only flips the mute`() {
        val c = control(player(volume = 0.4, muted = false))
        c.volume.toggleMute()
        assertEquals(listOf("setMuted"), names)
        assertEquals(true, rig.client.commands[0].third!!.getBoolean("muted"))
        rig.client.commands.clear()
        rig.client.set(ref("room"), player(volume = 0.4, muted = true))
        rig.settle()
        c.volume.toggleMute()
        assertEquals(listOf("setMuted"), names)
        assertEquals(false, rig.client.commands[0].third!!.getBoolean("muted"))
    }

    @Test
    fun `volume applied by the runtime shows up in the slider and the mute`() {
        val c = control(player(volume = 0.4), applyCommands = true)
        c.volume.commit(0.0)
        rig.settle()
        assertEquals(0.0, c.volume.shown.value, 0.0)
        assertTrue(c.volume.muted.value)
    }

    // --- press feedback ----------------------------------------------------------------------------------------

    @Test
    fun `a press is pending, for that key only`() {
        val c = control()
        c.press("togglePlay", "togglePlay")
        assertEquals(Feedback.Pending, c.feedbackFor("togglePlay"))
        assertNull(c.feedbackFor("next"))
        assertEquals(mapOf<String, Feedback>("togglePlay" to Feedback.Pending), c.feedback.value)
        assertEquals(listOf("togglePlay"), names)
    }

    @Test
    fun `a press sends its arguments`() {
        val c = control()
        c.press("shuffle", "setShuffle", json("shuffle" to true))
        c.press("seekBack", MediaPlayerCommands.previous())
        assertEquals(true, rig.client.commands[0].third!!.getBoolean("shuffle"))
        assertEquals("previous", names[1])
    }

    @Test
    fun `a press the player never takes up stops waiting after 4 seconds`() {
        val c = control()
        c.press("next", "next")
        rig.scheduler.advance(3_999)
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
        rig.scheduler.advance(1)
        assertNull(c.feedbackFor("next"))
        assertEquals(4_000L, PressTracker.PENDING_MS)
    }

    @Test
    fun `a press ends when the player visibly changes`() {
        val c = control(player(playback = PlaybackState.Paused))
        c.press("togglePlay", "togglePlay")
        rig.scheduler.advance(1_000)
        assertEquals(Feedback.Pending, c.feedbackFor("togglePlay"))
        rig.client.set(ref("room"), player(playback = PlaybackState.Playing))
        rig.settle()
        assertNull(c.feedbackFor("togglePlay"))
    }

    @Test
    fun `a skip ends when another track plays`() {
        val c = control(player(playback = PlaybackState.Playing, title = "One"))
        c.press("next", "next")
        rig.client.set(ref("room"), player(playback = PlaybackState.Playing, title = "One", position = 3.0))
        rig.settle()
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
        rig.client.set(ref("room"), player(playback = PlaybackState.Playing, title = "Two"))
        rig.settle()
        assertNull(c.feedbackFor("next"))
    }

    @Test
    fun `a failed press turns red for 700 ms`() {
        val c = control()
        rig.client.failNext("device busy")
        c.press("next", "next")
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
        rig.settle()
        assertEquals(Feedback.Error, c.feedbackFor("next"))
        rig.scheduler.advance(699)
        assertEquals(Feedback.Error, c.feedbackFor("next"))
        rig.scheduler.advance(1)
        assertNull(c.feedbackFor("next"))
        assertEquals(700L, PressTracker.ERROR_MS)
        // The pending timer is gone with it.
        rig.scheduler.advance(10_000)
        assertNull(c.feedbackFor("next"))
    }

    @Test
    fun `pressing again clears an error and restarts the wait`() {
        val c = control()
        rig.client.failNext("busy")
        c.press("next", "next")
        rig.settle()
        assertEquals(Feedback.Error, c.feedbackFor("next"))
        c.press("next", "next")
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
        rig.scheduler.advance(3_000)
        c.press("next", "next")
        rig.scheduler.advance(3_000)
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
        rig.scheduler.advance(1_000)
        assertNull(c.feedbackFor("next"))
    }

    @Test
    fun `an old failure does not end a newer press of the same key`() {
        val c = control()
        rig.client.failNext("first one failed")
        c.press("next", "next")
        c.press("next", "next")
        rig.settle()
        assertEquals(Feedback.Pending, c.feedbackFor("next"))
    }

    @Test
    fun `nothing happens after the scope is disposed`() {
        val c = control()
        c.press("next", "next")
        rig.scope.dispose()
        rig.scheduler.advance(10_000)
        assertEquals(0, rig.scheduler.pending)
    }

    // --- transportAvailability ---------------------------------------------------------------------------------

    private fun queue(items: List<dev.hashsome.client.QueueItem>, total: Int = items.size, offset: Int = 0) =
        QueueResult(items, total, offset)

    @Test
    fun `Next is off on the last track and on elsewhere`() {
        assertEquals(false, transportAvailability(queue(listOf(queued("a"), queued("b", true))), player(), 0.0).next)
        assertEquals(true, transportAvailability(queue(listOf(queued("a", true), queued("b"))), player(), 0.0).next)
        // The window can end before the queue does.
        assertEquals(true, transportAvailability(queue(listOf(queued("a", true)), total = 40), player(), 0.0).next)
    }

    @Test
    fun `Previous is off on the first track, unless it is far enough in to restart`() {
        val first = queue(listOf(queued("a", true), queued("b")))
        assertEquals(false, transportAvailability(first, player(), 1.0).previous)
        assertEquals(true, transportAvailability(first, player(), 30.0).previous)
        assertEquals(true, transportAvailability(first, player(), 3.0).previous)
        assertEquals(true, transportAvailability(queue(listOf(queued("a"), queued("b", true))), player(), 0.0).previous)
        // The first track of a window that starts later in the queue is not the first of the queue.
        assertEquals(true, transportAvailability(queue(listOf(queued("a", true)), total = 9, offset = 4), player(), 0.0).previous)
    }

    @Test
    fun `Play is off only with nothing queued, nothing loaded and nothing playing`() {
        assertEquals(false, transportAvailability(queue(emptyList()), player(), null).play)
        assertEquals(true, transportAvailability(queue(emptyList()), player(title = "x"), null).play)
        assertEquals(true, transportAvailability(queue(emptyList()), player(playback = PlaybackState.Playing), null).play)
        assertEquals(true, transportAvailability(queue(listOf(queued("a", true))), player(), null).play)
    }

    @Test
    fun `nothing is held back while the queue is unknown`() {
        assertEquals(TransportAvailability(next = true, previous = true, play = true), transportAvailability(null, player(), null))
        // A queue with no current item says nothing either.
        assertEquals(true, transportAvailability(queue(listOf(queued("a"))), player(), null).next)
    }
}

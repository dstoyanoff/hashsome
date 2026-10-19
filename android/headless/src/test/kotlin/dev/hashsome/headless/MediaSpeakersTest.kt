package dev.hashsome.headless

import dev.hashsome.client.Availability
import dev.hashsome.client.EntityKind
import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaGroup
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// entities/__tests__/media-speakers.test.tsx and offered-speakers.ts.
class MediaSpeakersTest {
    private val rig = Rig()

    private val caps = noCaps.copy(group = true)
    private val grouped = MediaGroup(leader = ref("porch"), members = listOf(ref("kitchen")))

    private fun rooms(overrides: Map<String, MediaPlayerEntity> = emptyMap()) {
        val all = mapOf(
            "porch" to player("porch", groupable = listOf(ref("kitchen"), ref("patio"), ref("garage")), caps = caps),
            "kitchen" to player("kitchen", caps = caps),
            "patio" to player("patio", playback = PlaybackState.Playing, title = "Blue Monday", caps = caps),
            "garage" to player("garage", availability = Availability.Unavailable, caps = caps),
        )

        for ((id, p) in all + overrides) rig.client.set(ref(id), p)
    }

    private fun speakers(me: String = "porch", allow: List<EntityRef> = emptyList()): SpeakersControl {
        rig.client.onCommand = null
        return SpeakersControl(rig.handle(ref(me), EntityKind.MediaPlayer), rig.scope, allow).also { rig.settle() }
    }

    private val SpeakersControl.refs get() = speakers.value.map { it.ref.value.removePrefix("ha:") }

    private val sent get() = rig.client.commands.map { Triple(it.first.value.removePrefix("ha:"), it.second, it.third) }

    @Test
    fun `the speakers offered are the ones it can be grouped with`() {
        rooms()
        val s = speakers()
        // The one playing a stream of its own first, then the quiet ones in the order the player gives them.
        assertEquals(listOf("patio", "kitchen", "garage"), s.refs)
        assertEquals(listOf("Patio", "Kitchen", "Garage"), s.speakers.value.map { it.name })
        assertTrue(s.speakers.value.none { it.joined })
    }

    @Test
    fun `an allowlist narrows them to the speakers it names that the player can be grouped with`() {
        rooms(mapOf("office" to player("office", caps = caps)))
        val s = speakers(allow = listOf(ref("kitchen"), ref("nobody"), ref("office")))
        // `nobody` is not there and `office` cannot be grouped with the porch, whatever the list says.
        assertEquals(listOf("kitchen"), s.refs)
    }

    @Test
    fun `an allowlist keeps the order the player gives them in`() {
        rooms()
        assertEquals(listOf("kitchen", "garage"), speakers(allow = listOf(ref("garage"), ref("kitchen"))).refs)
    }

    @Test
    fun `speakers in the stream are always shown, first, even outside the allowlist`() {
        rooms(mapOf("porch" to player("porch", group = grouped, groupable = listOf(ref("kitchen"), ref("patio")), caps = caps), "kitchen" to player("kitchen", group = grouped, caps = caps)))
        val s = speakers(allow = listOf(ref("patio")))
        assertEquals(listOf("kitchen", "patio"), s.refs)
        assertEquals(listOf(true, false), s.speakers.value.map { it.joined })
    }

    @Test
    fun `a follower sees the one that leads, and not itself`() {
        rooms(mapOf("porch" to player("porch", group = grouped, caps = caps), "kitchen" to player("kitchen", group = grouped, groupable = listOf(ref("porch"), ref("patio")), caps = caps)))
        val s = speakers(me = "kitchen")
        assertEquals(listOf("porch", "patio"), s.refs)
        assertEquals(listOf(true, false), s.speakers.value.map { it.joined })
    }

    @Test
    fun `speakers that play a stream of their own are listed before the quiet ones`() {
        rooms()
        val s = speakers()
        assertEquals(listOf("patio", "kitchen", "garage").sorted(), s.refs.sorted())
        assertEquals("patio", s.refs.first())
        assertEquals(SpeakerStatus(available = true, playing = "Blue Monday", leader = null, playsOwn = true), s.status.value[ref("patio")])
        assertEquals(SpeakerStatus(available = true, playing = null, leader = null, playsOwn = false), s.status.value[ref("kitchen")])
        assertEquals(false, s.status.value[ref("garage")]!!.available)
    }

    @Test
    fun `a speaker in a stream elsewhere says whose it is`() {
        val other = MediaGroup(ref("patio"), listOf(ref("garage")))
        rooms(mapOf("patio" to player("patio", group = other, caps = caps), "garage" to player("garage", group = other, playback = PlaybackState.Playing, caps = caps)))
        val s = speakers()
        assertEquals(ref("patio"), s.status.value[ref("garage")]!!.leader)
        assertNull(s.status.value[ref("patio")]!!.leader)
        assertEquals("something", s.status.value[ref("garage")]!!.playing)
    }

    @Test
    fun `a name arrives when the speaker does, and the id stands in until then`() {
        rig.client.set(ref("porch"), player("porch", groupable = listOf(ref("kitchen")), caps = caps))
        val s = speakers()
        assertEquals(listOf("kitchen"), s.speakers.value.map { it.name })
        rig.client.set(ref("kitchen"), player("kitchen").copy(name = "Kitchen Sonos"))
        rig.settle()
        assertEquals(listOf("Kitchen Sonos"), s.speakers.value.map { it.name })
    }

    @Test
    fun `speakers that are no longer offered are let go`() {
        rooms()
        val s = speakers()
        rig.client.set(ref("porch"), player("porch", groupable = listOf(ref("kitchen")), caps = caps))
        rig.settle()
        assertEquals(listOf("kitchen"), s.refs)
        assertEquals(setOf(ref("kitchen")), s.status.value.keys)
        rig.client.set(ref("patio"), player("patio", title = "Changed"))
        rig.settle()
        assertEquals(setOf(ref("kitchen")), s.status.value.keys)
    }

    // --- changes -------------------------------------------------------------------------------------------------

    @Test
    fun `adding a speaker asks the stream's leader to add it`() {
        rooms()
        speakers().add(ref("kitchen"))
        assertEquals(Triple("porch", "setGroupMembers", listOf("ha:kitchen")), sent[0].let { Triple(it.first, it.second, it.third!!.getJSONArray("add").let { a -> List(a.length()) { i -> a.getString(i) } }) })
    }

    @Test
    fun `a follower's add goes to the leader of its stream`() {
        rooms(mapOf("porch" to player("porch", group = grouped, caps = caps), "kitchen" to player("kitchen", group = grouped, groupable = listOf(ref("patio")), caps = caps)))
        speakers(me = "kitchen").add(ref("patio"))
        assertEquals("porch", sent[0].first)
        assertEquals("setGroupMembers", sent[0].second)
    }

    @Test
    fun `taking a speaker out asks the leader to remove it`() {
        rooms(mapOf("porch" to player("porch", group = grouped, caps = caps), "kitchen" to player("kitchen", group = grouped, caps = caps)))
        speakers().remove(ref("kitchen"))
        assertEquals("porch", sent[0].first)
        assertEquals("ha:kitchen", sent[0].third!!.getJSONArray("remove").getString(0))
    }

    @Test
    fun `a follower can leave, or take the stream over by removing the player that leads it`() {
        rooms(mapOf("porch" to player("porch", group = grouped, caps = caps), "kitchen" to player("kitchen", group = grouped, caps = caps)))
        val s = speakers(me = "kitchen")
        s.remove(ref("kitchen"))
        s.leave()
        assertEquals(listOf("kitchen" to "leaveGroup", "kitchen" to "leaveGroup"), sent.map { it.first to it.second })
        s.remove(ref("porch"))
        s.takeOver()
        assertEquals(listOf("kitchen" to "takeOverGroup", "kitchen" to "takeOverGroup"), sent.drop(2).map { it.first to it.second })
    }

    @Test
    fun `a leader with one other speaker leaves in one press, handing the stream to it`() {
        rooms(mapOf("porch" to player("porch", group = grouped, caps = caps), "kitchen" to player("kitchen", group = grouped, caps = caps)))
        speakers().leave()
        assertEquals(listOf("kitchen" to "takeOverGroup"), sent.map { it.first to it.second })
    }

    @Test
    fun `a leader with several others picks which one leads, and one alone has nothing to leave`() {
        val three = MediaGroup(ref("porch"), listOf(ref("kitchen"), ref("patio")))
        rooms(mapOf("porch" to player("porch", group = three, caps = caps), "kitchen" to player("kitchen", group = three, caps = caps), "patio" to player("patio", group = three, caps = caps)))
        val s = speakers()
        s.leave()
        assertEquals(0, sent.size)
        s.lead(ref("patio"))
        assertEquals(listOf("patio" to "takeOverGroup"), sent.map { it.first to it.second })

        rooms()
        rig.client.commands.clear()
        // Alone now.
        rig.client.set(ref("porch"), player("porch", caps = caps))
        rig.settle()
        s.leave()
        assertEquals(0, sent.size)
    }

    @Test
    fun `joining a stream asks its leader to add this player`() {
        val other = MediaGroup(ref("patio"), listOf(ref("garage")))
        rooms(mapOf("patio" to player("patio", group = other, caps = caps), "garage" to player("garage", group = other, playback = PlaybackState.Playing, caps = caps)))
        val s = speakers(me = "kitchen")
        rig.client.set(ref("kitchen"), player("kitchen", groupable = listOf(ref("patio"), ref("garage")), caps = caps))
        rig.settle()
        s.join(ref("garage"))
        assertEquals("patio", sent[0].first)
        assertEquals("ha:kitchen", sent[0].third!!.getJSONArray("add").getString(0))
        s.join(ref("patio"))
        assertEquals("patio", sent[1].first)
    }

    @Test
    fun `a change of the group waits for the group to change, and gives up after a while`() {
        rooms()
        val s = speakers()
        s.add(ref("kitchen"))
        assertEquals(Feedback.Pending, s.feedback.value["group:add:ha:kitchen"])
        rig.settle()
        rig.client.set(ref("porch"), player("porch", group = grouped, groupable = listOf(ref("kitchen")), caps = caps))
        rig.settle()
        assertEquals(emptyMap<String, Feedback>(), s.feedback.value)

        rig.client.set(ref("porch"), player("porch", groupable = listOf(ref("kitchen")), caps = caps))
        rig.settle()
        s.add(ref("kitchen"))
        rig.scheduler.advance(7_999)
        assertEquals(Feedback.Pending, s.feedback.value["group:add:ha:kitchen"])
        rig.scheduler.advance(1)
        assertEquals(emptyMap<String, Feedback>(), s.feedback.value)
    }

    @Test
    fun `a change the runtime refuses shows red for a moment`() {
        rooms()
        val s = speakers()
        rig.client.failNext("not allowed")
        s.add(ref("kitchen"))
        rig.settle()
        assertEquals(Feedback.Error, s.feedback.value["group:add:ha:kitchen"])
        rig.scheduler.advance(700)
        assertEquals(emptyMap<String, Feedback>(), s.feedback.value)
    }

    @Test
    fun `a player that is not there offers nothing and sends nothing`() {
        val s = SpeakersControl(rig.handle(ref("ghost"), EntityKind.MediaPlayer), rig.scope, emptyList())
        rig.settle()
        assertEquals(emptyList<Speaker>(), s.speakers.value)
        s.add(ref("kitchen"))
        s.remove(ref("kitchen"))
        s.leave()
        s.takeOver()
        assertEquals(0, rig.client.commands.size)
    }
}

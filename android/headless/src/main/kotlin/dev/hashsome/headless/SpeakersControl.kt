package dev.hashsome.headless

import dev.hashsome.client.EntityRef
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlaybackState
import dev.hashsome.client.Snapshot
import dev.hashsome.client.Store

/** One speaker offered for grouping, and whether it already plays with this player. */
data class Speaker(val ref: EntityRef, val name: String, val joined: Boolean)

/** What a speaker is doing, to tell the ones with a stream of their own from the quiet ones (`media-speakers.tsx`). */
data class SpeakerStatus(
    /** Reachable: it can be added or joined. */
    val available: Boolean,

    /** The title it is playing, while it plays (`something` when it has none). */
    val playing: String?,

    /** The player whose stream it is in, when that is another one. */
    val leader: EntityRef?,

    /** It plays a stream of its own: it is playing, or it leads or follows a group. */
    val playsOwn: Boolean,
)

/**
 * The speakers of a player's stream and the ones it could be grouped with (mirrors `offered-speakers.ts` and
 * `media-speakers.tsx`). [allow] narrows the speakers on offer to those named in it and that the player can be grouped
 * with; an empty list narrows nothing. Speakers already in the stream are always listed, so they can be taken out.
 */
class SpeakersControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope, val allow: List<EntityRef>) {
    /** The speakers to show: those in the stream first, then those in [allow] this player can be grouped with. */
    val speakers: Store<List<Speaker>> = Store(emptyList())

    /** What each of [speakers] is doing, by ref. */
    val status: Store<Map<EntityRef, SpeakerStatus>> = Store(emptyMap())

    /** The group presses that are waiting or failed, by key (`group:add:<ref>`, `group:remove:<ref>`, `group:reset`, `group:handover`, `group:takeover`, `group:lead:<ref>`, `group:join:<ref>`). */
    val feedback: Store<Map<String, Feedback>> get() = presses.feedback

    private val presses = PressTracker(scope, handle)
    private val watching = HashMap<EntityRef, Scope>()
    private val players = HashMap<EntityRef, MediaPlayerEntity?>()

    init {
        scope.bind(handle.state) {
            sync()
            recompute()
        }
    }

    /** Adds [speaker] to this player's stream (asks the stream's leader to). */
    fun add(speaker: EntityRef) {
        val head = head() ?: return
        presses.press("group:add:$speaker", GROUP_PENDING_MS) { done ->
            scope.command(head, MediaPlayerCommands.setGroupMembers(add = listOf(speaker)), done)
        }
    }

    /**
     * Takes [speaker] out of the stream. For a player that follows, taking out itself leaves ([leave]) and taking out
     * the one that leads takes the stream over ([takeOver]); otherwise the stream's leader is asked to remove it.
     */
    fun remove(speaker: EntityRef) {
        val me = handle.ref ?: return
        val group = handle.entity?.group
        when {
            speaker == me -> leave()
            group != null && group.leader != me && speaker == group.leader -> takeOver()
            else -> {
                val head = head() ?: return
                presses.press("group:remove:$speaker", GROUP_PENDING_MS) { done ->
                    scope.command(head, MediaPlayerCommands.setGroupMembers(remove = listOf(speaker)), done)
                }
            }
        }
    }

    /**
     * This player leaves the group it plays in. A follower just leaves; a leader with one other speaker hands the stream
     * to it (which takes it over, and the stream goes on without this player). A leader with several others has to say
     * who gets it ([lead]), and one alone has nothing to leave.
     */
    fun leave() {
        val me = handle.ref ?: return
        val group = handle.entity?.group ?: return
        if (group.leader != me) {
            presses.press("group:reset", GROUP_PENDING_MS) { done ->
                scope.command(me, MediaPlayerCommands.leaveGroup(), done)
            }

            return
        }

        val heir = group.members.filter { it != me }.singleOrNull() ?: return
        presses.press("group:handover", GROUP_PENDING_MS) { done ->
            scope.command(heir, MediaPlayerCommands.takeOverGroup(), done)
        }
    }

    /** For a player that follows another: takes the stream over. */
    fun takeOver() {
        val me = handle.ref ?: return
        if (handle.entity == null) return
        presses.press("group:takeover", GROUP_PENDING_MS) { done ->
            scope.command(me, MediaPlayerCommands.takeOverGroup(), done)
        }
    }

    /** A leader hands the stream to [heir] and leaves it. */
    fun lead(heir: EntityRef) {
        presses.press("group:lead:$heir", GROUP_PENDING_MS) { done ->
            scope.command(heir, MediaPlayerCommands.takeOverGroup(), done)
        }
    }

    /** This player joins the stream [speaker] plays in (asks its leader to add this player). */
    fun join(speaker: EntityRef) {
        val me = handle.ref ?: return
        if (handle.entity == null) return
        val leader = players[speaker]?.group?.leader ?: speaker
        presses.press("group:join:$speaker", GROUP_PENDING_MS) { done ->
            scope.command(leader, MediaPlayerCommands.setGroupMembers(add = listOf(me)), done)
        }
    }

    // Where the changes to the stream go: to the player it belongs to, which is this one unless it follows.
    private fun head(): EntityRef? = handle.entity?.let { it.group?.leader ?: it.ref }

    private fun inStream(): List<EntityRef> {
        val me = handle.ref
        val group = handle.entity?.group ?: return emptyList()
        val all = if (group.leader != me) listOf(group.leader) + group.members else group.members
        return all.filter { it != me }
    }

    private fun offered(): List<EntityRef> {
        val me = handle.ref
        val can = handle.entity?.groupable ?: emptyList()
        return (if (allow.isEmpty()) can else can.filter { it in allow }).filter { it != me }
    }

    // Watches the speakers listed, and lets go of those no longer listed.
    private fun sync() {
        val wanted = LinkedHashSet<EntityRef>()
        wanted += inStream()
        wanted += offered()
        for (ref in wanted) {
            if (ref in watching) continue
            val child = scope.child()
            watching[ref] = child
            child.watch(ref) { snapshot ->
                players[ref] = (snapshot as? Snapshot.Present)?.entity as? MediaPlayerEntity
                recompute()
            }
        }

        for (ref in watching.keys.toList()) {
            if (ref !in wanted) {
                watching.remove(ref)?.dispose()
                players.remove(ref)
            }
        }
    }

    private fun recompute() {
        val others = inStream()
        val candidates = offered().filter { it !in others }
        val infos = (others + candidates).associateWith { statusOf(it) }
        val (elsewhere, quiet) = candidates.partition { infos[it]?.playsOwn == true }
        speakers.set((others + elsewhere + quiet).map { Speaker(it, players[it]?.name ?: it.id, joined = it in others) })
        status.set(infos)
    }

    private fun statusOf(ref: EntityRef): SpeakerStatus {
        val player = players[ref]
        val available = player?.availability == dev.hashsome.client.Availability.Ready
        val playing = available && player?.playback == PlaybackState.Playing
        val group = player?.group
        return SpeakerStatus(
            available = available,
            playing = if (playing) (player?.media?.title ?: "something") else null,
            leader = group?.leader?.takeIf { it != ref },
            playsOwn = available && (playing || group != null),
        )
    }

    companion object {
        /** How long a change of the group is shown as waiting at most; the player takes a few seconds to apply it. */
        const val GROUP_PENDING_MS = 8_000L
    }
}

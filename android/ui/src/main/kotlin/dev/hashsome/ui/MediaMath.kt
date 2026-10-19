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

// The arithmetic and the wording of the media components, with nothing from the platform in it, so all of it runs (and is
// tested) on a plain JVM. The views call these when they draw or when a finger moves.

/** The words the media components say, as the web says them (`media-progress.ts`, `media-source.ts`, `media-browser.tsx`). */
object MediaFormat {
    /** A position: `1:23`, `1:02:03` once an hour long. */
    fun duration(seconds: Double): String {
        val total = Math.max(0.0, Math.floor(seconds)).toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val rest = (total % 60).toString().padStart(2, '0')
        return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$rest" else "$minutes:$rest"
    }

    /** How long something plays, said plainly: `42 min`, `2 h`, `7 h 33 min`. For a whole queue or album, not a position. */
    fun length(seconds: Double): String {
        val minutes = Math.round(Math.max(0.0, seconds) / 60)
        if (minutes < 60) return "${Math.max(1L, minutes)} min"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0L) "$hours h" else "$hours h $rest min"
    }

    /** What a queue was filled from, for its header: `Playlist · Daily Mix 2`, or just the name when its kind is not known. */
    fun source(source: MediaSource): String {
        val word = when (source.kind) {
            SourceKind.Playlist -> "Playlist"
            SourceKind.Album -> "Album"
            SourceKind.Artist -> "Artist"
            SourceKind.Radio -> "Radio station"
            null -> return source.title
        }
        return "$word · ${source.title}"
    }

    /**
     * What is in an album, a playlist or an artist that has been opened, in a few words: `42 songs · 2 h 51 min`, `12 albums`.
     * The length is only said when every track has one, so it is never a total of some of them.
     */
    fun summary(items: List<BrowseItem>): String {
        if (items.isEmpty()) return "0 items"
        val kinds = items.map { it.kind }.toSet()
        val (one, many) = (if (kinds.size == 1) noun(items[0].kind) else null) ?: ("item" to "items")
        val count = "${items.size} ${if (items.size == 1) one else many}"
        val lengths = items.map { it.duration }
        return if (kinds.size == 1 && items[0].kind == BrowseKind.Track && lengths.all { it != null }) {
            "$count · ${length(lengths.sumOf { it ?: 0.0 })}"
        } else {
            count
        }
    }

    private fun noun(kind: BrowseKind): Pair<String, String>? = when (kind) {
        BrowseKind.Track -> "song" to "songs"
        BrowseKind.Album -> "album" to "albums"
        BrowseKind.Playlist -> "playlist" to "playlists"
        BrowseKind.Artist -> "artist" to "artists"
        BrowseKind.Radio -> "station" to "stations"
        else -> null
    }

    /** The name of each kind of thing a search finds, on the chip that narrows the results to it. */
    fun kindLabel(kind: BrowseKind): String = when (kind) {
        BrowseKind.Album -> "Albums"
        BrowseKind.Artist -> "Artists"
        BrowseKind.Track -> "Tracks"
        BrowseKind.Playlist -> "Playlists"
        BrowseKind.Radio -> "Radio"
        BrowseKind.Folder -> "Folders"
        BrowseKind.Other -> "Other"
    }

    /** The queue's heading count: ` · 12 tracks · 48 min`. */
    fun queueCount(total: Int, duration: Double?): String =
        " · $total ${if (total == 1) "track" else "tracks"}" + if (duration != null && total > 0) " · ${length(duration)}" else ""

    /**
     * What the speakers pill says: how many speakers play together, or which stream is playing in another room, or nothing.
     * "Speakers" with nothing going on, "3 speakers" in a group, "Patio playing" or "2 streams playing".
     */
    fun speakers(together: Int, elsewhere: List<String>): String {
        if (together > 1) return "$together speakers"
        if (elsewhere.size == 1) return "${elsewhere[0]} playing"
        if (elsewhere.size > 1) return "${elsewhere.size} streams playing"
        return "Speakers"
    }
}

/**
 * Seeking by dragging round the artwork's ring (`artwork-ring.tsx`). A press starts where it lands; from there the position
 * follows how far round the finger has gone, not which side of the ring it is on, and stops at either end: carrying on past the top
 * (where the start and the end meet) holds it, instead of jumping to the other end, until the finger is back round.
 */
class RingSeek(private val duration: Double) {
    private var turn = 0.0
    private var along = 0.0

    /** A press at [turn] (0..1 from the top, clockwise): the position it starts at, in seconds. */
    fun start(turn: Double): Double {
        this.turn = turn
        along = turn
        return Math.round(turn * duration).toDouble()
    }

    /** The finger is at [now]: the position in seconds. */
    fun follow(now: Double): Double {
        // The way round that is the short one, so crossing the top does not read as a whole turn.
        var step = now - turn
        if (step > 0.5) step -= 1.0 else if (step < -0.5) step += 1.0
        // Only a little past either end is remembered, so the finger is never far from its place.
        along = (along + step).coerceIn(-0.25, 1.25)
        turn = now
        return Math.round(along.coerceIn(0.0, 1.0) * duration).toDouble()
    }

    companion object {
        /** How far round the ring a point is, from the top, clockwise: 0 up to 1. [dx] and [dy] are from the ring's center (y down). */
        fun turnAt(dx: Double, dy: Double): Double = (Math.atan2(dx, -dy) / (2 * Math.PI) + 1) % 1

        /** Only the ring itself seeks, not the artwork inside it: a press this far out from the center (a share of the ring's size) is on it. */
        fun onRing(distance: Double, size: Double): Boolean = distance >= size * 0.4
    }
}

/** What can be done with a row of the library besides tapping it (`BrowseRowView` in `media-browser.tsx`). */
enum class RowButton { Next, Add, Play, Toggle, Spare }

/** What a tap on the body of a library row does. */
enum class RowMain { Open, Play, Toggle, None }

object BrowseActions {
    /** A row opens what can be opened and otherwise plays; the item playing carries the player's play/pause instead of another start. */
    fun main(expandable: Boolean, playable: Boolean, active: Boolean): RowMain = when {
        expandable -> RowMain.Open
        playable -> if (active) RowMain.Toggle else RowMain.Play
        else -> RowMain.None
    }

    /**
     * The buttons at the right of a row, left to right. A player with a queue can play an item next and add it (any playable item);
     * an album or playlist, which opens, also gets a play button. The active item shows the player's play/pause, and keeps the room
     * the others would take, empty, so the text and the chevron line up with the rows around it.
     */
    fun buttons(expandable: Boolean, playable: Boolean, queueable: Boolean, active: Boolean): List<RowButton> {
        if (active) {
            val spare = Math.max(0, (if (queueable && playable) 2 else 0) + (if (expandable && playable) 1 else 0) - 1)
            return List(spare) { RowButton.Spare } + RowButton.Toggle
        }
        val out = ArrayList<RowButton>(3)
        if (queueable && playable) {
            out += RowButton.Next
            out += RowButton.Add
        }
        if (expandable && playable) out += RowButton.Play
        return out
    }
}

/** Where the parts of a library row are along its width, for drawing it and for finding what a touch is on. All in px. */
class RowGeometry(val width: Float, private val circle: Float, private val gap: Float, private val buttons: Int) {
    /** The body of the row (the part that opens or plays) ends here. */
    val mainWidth: Float get() = width - buttons * (circle + gap)

    /** Where button [i] (counting from the left) starts. */
    fun buttonLeft(i: Int): Float = mainWidth + gap + i * (circle + gap)

    /** -1 for the gap between parts, 0 for the body, and 1 + i for button i. */
    fun regionAt(x: Float): Int {
        if (x < mainWidth) return 0
        for (i in 0 until buttons) {
            val left = buttonLeft(i)
            if (x >= left && x < left + circle) return 1 + i
        }
        return -1
    }
}

/** Where the parts of a queue row are. */
class QueueGeometry(val width: Float, private val grip: Float, private val remove: Float) {
    /** A press this far in from the left of a row that can be reordered is on its handle. */
    fun onGrip(x: Float): Boolean = x < grip

    /** A press this far from the right edge is on the cross. */
    fun onRemove(x: Float): Boolean = x > width - remove
}

/** Dragging a track in the queue by its handle (`startDrag` in `media-queue.tsx`): every row is one height, so the place it is over is arithmetic. */
object QueueDrag {
    /** The place a row dragged from [from] by [offset] px is over, given the distance [pitch] between two rows. */
    fun target(from: Int, offset: Float, pitch: Float, last: Int): Int =
        Math.round(from + offset / pitch).coerceIn(0, Math.max(0, last))

    /** How the row at [index] gives way to the one dragged from [from] to [target]: one place up (-1), down (1), or not at all (0). */
    fun shift(index: Int, from: Int, target: Int): Int = when {
        index == from -> 0
        from < target && index > from && index <= target -> -1
        target < from && index >= target && index < from -> 1
        else -> 0
    }

    /** [ids] with the one at [from] taken to [to]. */
    fun reorder(ids: List<String>, from: Int, to: Int): List<String> {
        val next = ArrayList(ids)
        next.add(to, next.removeAt(from))
        return next
    }
}

/** A queue row leaving (or coming back, when the player refused): it fades and gives up its height, ease-out, in [MS] ms. */
/**
 * How wide a lone title is drawn in a [room] that is a little short of what it measures. The face's advances measure about 2% wider on
 * the device than the browser lays the same words out (137 px against 134 for "Nothing playing" at 12 px x 1.5), so a title that
 * overflows by no more than that is drawn whole, into the gap beside it, rather than ending in an ellipsis the web does not show.
 */
object TitleFit {
    const val SLACK = 0.025f

    /** The most a title of [natural] px may overflow its room and still be drawn whole. */
    fun slack(natural: Int): Int = Math.ceil((natural * SLACK).toDouble()).toInt()

    /** The width to give a title of [natural] px in [room] px: its own when it is within the slack, else the room (it ends in an ellipsis). */
    fun width(natural: Int, room: Int): Int = if (natural <= room + slack(natural)) natural else room
}

/** The order of a list whose places come and go: the ones that stay in their new order, a leaver where it was, a newcomer where it belongs. */
object RowOrder {
    /**
     * [wanted] in order, with each key of [old] that is not in it kept right after the key that preceded it in [old] (or first, when none
     * that stays did), so a row that is leaving collapses where it stood while the rest take their places.
     */
    fun merge(old: List<String>, wanted: List<String>): List<String> {
        val keep = wanted.toHashSet()
        val after = HashMap<String?, ArrayList<String>>()
        var anchor: String? = null
        for (k in old) {
            if (k in keep) {
                anchor = k
            } else {
                after.getOrPut(anchor) { ArrayList() }.add(k)
            }
        }
        val out = ArrayList<String>(wanted.size + old.size)
        after[null]?.let { out.addAll(it) }
        for (k in wanted) {
            out.add(k)
            after[k]?.let { out.addAll(it) }
        }
        return out
    }

    /** [keys] without those that are no longer [alive] (a row dropped while the list was being brought up to date). */
    fun live(keys: List<String>, alive: (String) -> Boolean): List<String> = keys.filter(alive)
}

object RowCollapse {
    const val MS = 160L

    /** Ease-out (cubic) of [t], 0..1. */
    fun eased(t: Float): Float {
        val r = 1f - t.coerceIn(0f, 1f)
        return 1f - r * r * r
    }

    /** Where a collapse that went from [from] toward [to] is [elapsed] ms in: 0 is a whole row, 1 is gone. */
    fun at(from: Float, to: Float, elapsed: Long, ms: Long = MS): Float = from + (to - from) * eased(elapsed.toFloat() / ms)

    /** The height (or margin) of a row of [full] px that has collapsed [p] of the way. */
    fun size(full: Int, p: Float): Int = Math.round(full * (1f - p.coerceIn(0f, 1f)))

    /** The fade of a row that is [base] opaque. */
    fun alpha(base: Float, p: Float): Float = base * (1f - p.coerceIn(0f, 1f))
}

/**
 * Which item a player is being asked to play, by title, so its bar can say "Loading <title>…" while the player takes many
 * seconds to start an album. The press tracker knows the item's id only; the library that made the press remembers its title.
 */
object PlayRequests {
    private val titles = HashMap<String, HashMap<String, String>>()

    /** The library asked [ref] to play the item [id], called [title]. */
    fun note(ref: EntityRef?, id: String, title: String) {
        if (ref == null) return
        val own = titles.getOrPut(ref.value) { HashMap() }
        // A library of thousands is never remembered whole: the player's last few asks are all a bar can be waiting for.
        if (own.size >= REMEMBERED) own.clear()
        own[id] = title
    }

    /** The title of the item that is being started on [ref], or an empty string when one is but its title is not known; null when none is. */
    fun loading(ref: EntityRef?, feedback: Map<String, Feedback>): String? {
        for ((key, state) in feedback) {
            if (state != Feedback.Pending) continue
            val mode = key.substringBefore(':', "")
            if (mode != "play" && mode != "replace") continue
            return titles[ref?.value]?.get(key.substringAfter(':')) ?: ""
        }
        return null
    }

    private const val REMEMBERED = 40
}

/** How a player's speakers stand: how many play together (itself included), or the streams playing on the ones it could join. */
data class SpeakersSummary(val together: Int, val elsewhere: List<String>) {
    companion object {
        /**
         * Counts what `useSpeakersSummary` does: in a group, the speakers of it; alone, one stream per leader of the speakers on offer
         * that play, named by whoever leads it.
         */
        fun of(group: MediaGroup?, speakers: List<Speaker>, status: Map<EntityRef, SpeakerStatus>): SpeakersSummary {
            if (group != null) return SpeakersSummary(group.members.size + 1, emptyList())
            val names = speakers.associate { it.ref to it.name }
            val streams = LinkedHashMap<EntityRef, String>()
            for (speaker in speakers) {
                val info = status[speaker.ref] ?: continue
                if (info.playing == null) continue
                val leader = info.leader ?: speaker.ref
                if (leader !in streams) streams[leader] = names[leader] ?: speaker.name
            }
            return SpeakersSummary(0, streams.values.toList())
        }
    }

    /** The words for the pill. */
    val label: String get() = MediaFormat.speakers(together, elsewhere)
}

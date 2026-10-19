package dev.hashsome.headless

import dev.hashsome.client.BrowseItem
import dev.hashsome.client.BrowseKind
import dev.hashsome.client.Cancel
import dev.hashsome.client.MediaPlayerCommands
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.Outcome
import dev.hashsome.client.PlayMode
import dev.hashsome.client.Query
import dev.hashsome.client.Store

/** One top-level section of a library (Playlists, Albums...). */
data class BrowserTab(val title: String, val path: String?, val holds: dev.hashsome.client.BrowseKind?)

/** One level opened below a tab: what was opened and the title it showed. */
data class BrowserLevel(val item: BrowseItem, val title: String)

data class BrowserState(
    val tabs: List<BrowserTab> = emptyList(),
    val active: Int = 0,
    /** The levels opened below the active tab, innermost last. */
    val stack: List<BrowserLevel> = emptyList(),
    val rows: List<BrowseItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    /** The search text, when a search is on. */
    val search: String? = null,
)

/**
 * How many rows of a long list are on screen while it opens: one, then one more every 55 ms up to
 * 10, then ten at a time, so the first rows appear at once and a big list does not stall a frame
 * (mirrors `use-rows.ts`). The ten at a time are asked for with [more], when the list is scrolled near its end.
 */
class RowsArrival(private val scope: Scope) {
    val visible: Store<Int> = Store(0)

    private var total = 0
    private var timer: Cancel? = null

    /** Starts the staggered arrival of [total] rows. A list of a few rows is shown whole at once. */
    fun start(total: Int) {
        timer?.invoke()
        timer = null
        this.total = total
        if (total <= WHOLE_ROWS) {
            visible.set(total)
            return
        }

        visible.set(OPENING_ROWS)
        val target = Math.min(total, FIRST_ROWS)
        timer = scope.every(STEP_MS) {
            val next = Math.min(target, visible.value + STEP_ROWS)
            if (next > visible.value) visible.set(next)
            if (next >= target) {
                timer?.invoke()
                timer = null
            }
        }
    }

    /** The end of the list is near: ten more rows. */
    fun more() {
        if (visible.value >= total) return
        visible.set(Math.min(total, visible.value + MORE_ROWS))
    }

    companion object {
        /** How many rows a list has once it has opened. */
        const val FIRST_ROWS = 10

        /** How many more rows each [more] adds. */
        const val MORE_ROWS = 10

        /** A list opens with this many rows... */
        const val OPENING_ROWS = 1

        /** ...unless it has no more than this many, which are shown whole. */
        const val WHOLE_ROWS = 4

        /** The rows that arrive in each step. */
        const val STEP_ROWS = 1

        /** The time between one row and the next while the list opens, in ms. */
        const val STEP_MS = 55L
    }
}

private val WORD_BREAK = Regex("[\\s\\-_/.,:;()\\[\\]]+")

/**
 * How well an item's title answers what was searched for, lowest first: the whole title, then its start, then a word's
 * start, then anywhere in it, then not in the title at all (found by its artist, say).
 */
private fun matchRank(title: String, needle: String): Int {
    val text = title.lowercase()
    val wanted = needle.lowercase()
    return when {
        text == wanted -> 0
        text.startsWith(wanted) -> 1
        text.split(WORD_BREAK).any { it.startsWith(wanted) } -> 2
        text.contains(wanted) -> 3
        else -> 4
    }
}

/**
 * Search results in the order of how well their titles match, an exact match first. Items that match equally keep the
 * order the library gave them, which is its own idea of what matters.
 */
fun rankSearchResults(items: List<BrowseItem>, needle: String): List<BrowseItem> =
    items.withIndex().sortedWith(compareBy({ matchRank(it.value.title, needle) }, { it.index })).map { it.value }

/**
 * Walks a player's own media library through the runtime (mirrors `use-media-browser.ts`). The top level is read once:
 * shelves become tabs (the first is open to begin with), anything else is a plain list. Below it, a stack of opened
 * folders; apart from it, an optional search, and clearing the search returns to where the browser was.
 */
class MediaBrowserControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    val state: Store<BrowserState> = Store(BrowserState())
    val arrival: RowsArrival = RowsArrival(scope)

    /** The items asked to be played and not yet taken up by the player, by `<mode>:<item id>`; it ends a few seconds after the ask or when the player changes. */
    val feedback: Store<Map<String, Feedback>> get() = presses.feedback

    private class Answer(val key: String, val items: List<BrowseItem>, val title: String?, val error: String?)

    // A level and, when it was opened from search results, the search to return to.
    private class Level(val level: BrowserLevel, val from: String?)

    private class Shown(
        val tabs: List<BrowseItem>?,
        val selected: Int,
        val needle: String,
        val searching: Boolean,
        val kind: BrowseKind,
        val key: String,
        val request: Query.Browse?,
        val listing: Answer?,
    )

    private val presses = PressTracker(scope, handle)
    private var top: Answer? = null
    private var answer: Answer? = null
    private var text = ""
    private var active = 0
    private val levels = ArrayList<Level>()

    // The phrase last sent: typing waits for a pause; opening a folder or a tab does not.
    private var sent = ""
    private var requestedKey: String? = null
    private var pauseTimer: Cancel? = null
    private var inFlight: Cancel? = null
    private var asked = 0
    private var topInFlight: Cancel? = null
    private var topAsked = 0
    private var startedFor: String? = null

    init {
        loadTop()
        scope.onReconnect {
            loadTop()
            changed(force = true)
        }
    }

    fun openTab(index: Int) {
        val count = shown().tabs?.size ?: 0
        if (index !in 0 until count) return
        active = index
        text = ""
        levels.clear()
        changed()
    }

    /** Opens an expandable item below the current level. */
    fun open(item: BrowseItem) {
        val from = text.trim().takeIf { it.isNotEmpty() }
        levels += Level(BrowserLevel(item, item.title), from)
        text = ""
        changed()
    }

    /** Goes up one level; false when already at a tab's top. */
    fun back(): Boolean {
        if (text.trim().isNotEmpty()) {
            text = ""
            changed()
            return true
        }

        if (levels.isEmpty()) return false
        val left = levels.removeAt(levels.size - 1)
        if (left.from != null) text = left.from
        changed()
        return true
    }

    /** Searches the library (null or blank clears the search). Results are ranked as on the web. */
    fun search(text: String?) {
        this.text = text ?: ""
        changed()
    }

    /**
     * Plays [item] from this player's library. A track opened from an album or a playlist plays in it (the album goes
     * on after the track), as on the web. The wait for the player shows in [feedback] under `<mode>:<item id>`.
     */
    fun play(item: BrowseItem, mode: PlayMode = PlayMode.Play) {
        val container = levels.lastOrNull()?.level?.item
        val within = if (
            item.kind == BrowseKind.Track && container != null &&
            (container.kind == BrowseKind.Album || container.kind == BrowseKind.Playlist) &&
            (mode == PlayMode.Play || mode == PlayMode.Replace)
        ) {
            container
        } else {
            null
        }

        val limit = if (mode == PlayMode.Play || mode == PlayMode.Replace) PLAY_PENDING_MS else QUEUE_PENDING_MS
        presses.press("${mode.wire}:${item.id}", limit) { done ->
            handle.command(MediaPlayerCommands.playMedia(item.id, mode, context = within?.id)) { outcome ->
                // Added to or played from the library, the queue changes without the track changing.
                if (outcome is Outcome.Ok) handle.ref?.let { QueueEpoch.bump(scope.client, it) }
                done(outcome)
            }
        }
    }

    // --- what is shown ------------------------------------------------------------------------------------------

    private fun shown(): Shown {
        val tabItems = top?.takeIf { it.error == null && isShelves(it.items) }?.items
        val selected = if (tabItems != null) active.coerceIn(0, tabItems.size - 1) else 0
        val needle = text.trim()
        val searching = needle.isNotEmpty()
        val here = levels.lastOrNull()
        val atTop = levels.isEmpty()

        // A search is for one kind at a time: the open shelf's, when it holds one, or tracks.
        val holds = tabItems?.get(selected)?.holds
        val offered = ALWAYS_SEARCHABLE + (tabItems?.mapNotNull { it.holds } ?: emptyList())
        val kind = if (holds != null && holds in offered) holds else BrowseKind.Track

        val path = if (atTop) tabItems?.get(selected)?.id else here?.level?.item?.id
        val key = if (searching) "search:$needle:${kind.wire}" else "path:${path ?: ""}"
        val result = answer?.takeIf { it.key == key }
        val listing = if (!searching && atTop && tabItems == null) top else result
        val request = when {
            searching -> Query.Browse(search = needle, kind = kind)
            path != null -> Query.Browse(path = path)
            else -> null
        }

        return Shown(tabItems, selected, needle, searching, kind, key, request, listing)
    }

    private fun publish() {
        val s = shown()
        val ranked = s.listing?.items?.let { if (s.searching) rankSearchResults(it, s.needle) else it } ?: emptyList()
        state.set(
            BrowserState(
                tabs = s.tabs?.map { BrowserTab(it.title, it.id, it.holds) } ?: emptyList(),
                active = s.selected,
                stack = levels.map { it.level },
                rows = ranked,
                loading = s.listing == null,
                error = s.listing?.error,
                search = if (s.searching) text else null,
            ),
        )

        // A list that has just arrived opens a row at a time; the same list answering again does not start over.
        if (s.listing == null) {
            startedFor = null
        } else if (startedFor != s.key) {
            startedFor = s.key
            arrival.start(ranked.size)
        }
    }

    /** Something changed: ask for what is now shown if it is not what was asked for, and say what is shown. */
    private fun changed(force: Boolean = false) {
        val s = shown()
        val request = s.request
        if (request == null) {
            pauseTimer?.invoke()
            pauseTimer = null
            inFlight?.invoke()
            inFlight = null
            requestedKey = null
        } else if (force || s.key != requestedKey) {
            requestedKey = s.key
            pauseTimer?.invoke()
            pauseTimer = null
            inFlight?.invoke()
            inFlight = null
            val pause = if (s.searching && sent != s.needle) SEARCH_PAUSE_MS else 0L
            sent = if (s.searching) s.needle else ""
            if (pause == 0L) send(s.key, request) else pauseTimer = scope.after(pause) {
                pauseTimer = null
                send(s.key, request)
            }
        }

        publish()
    }

    private fun send(key: String, request: Query.Browse) {
        val ref = handle.ref ?: return
        val mine = ++asked
        inFlight = scope.query(ref, request) { result ->
            if (mine != asked) return@query
            inFlight = null
            answer = result.fold(
                onSuccess = { Answer(key, it.items, it.title, null) },
                onFailure = { Answer(key, emptyList(), null, it.message ?: it.toString()) },
            )

            publish()
        }
    }

    private fun loadTop() {
        val ref = handle.ref ?: return
        topInFlight?.invoke()
        val mine = ++topAsked
        topInFlight = scope.query(ref, Query.Browse()) { result ->
            if (mine != topAsked) return@query
            topInFlight = null
            top = result.fold(
                onSuccess = { Answer("top", it.items, it.title, null) },
                onFailure = { Answer("top", emptyList(), null, it.message ?: it.toString()) },
            )

            changed()
        }
    }

    companion object {
        /** How long typing stops before a search is sent. */
        const val SEARCH_PAUSE_MS = 300L

        /** How long a play from the library is shown as waiting at most (a player takes many seconds to start an album). */
        const val PLAY_PENDING_MS = 15_000L

        /** How long adding to the queue is shown as waiting at most. */
        const val QUEUE_PENDING_MS = 2_000L

        /** What a search is offered for whatever the library, and then for each kind a shelf says it holds (radio, say). */
        private val ALWAYS_SEARCHABLE = listOf(BrowseKind.Track, BrowseKind.Playlist, BrowseKind.Album, BrowseKind.Artist)

        /** A top level that is nothing but folders to look inside is a set of shelves, which read better as tabs than as a list that always looks the same. */
        private fun isShelves(items: List<BrowseItem>) = items.size >= 2 && items.all { it.expandable && !it.playable }
    }
}

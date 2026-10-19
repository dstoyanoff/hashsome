package dev.hashsome.headless

import dev.hashsome.client.BrowseItem
import dev.hashsome.client.MediaPlayerEntity
import dev.hashsome.client.PlayMode
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
 * (mirrors `use-rows.ts`).
 */
class RowsArrival(private val scope: Scope) {
    val visible: Store<Int> = Store(0)

    /** Starts the staggered arrival of [total] rows. */
    fun start(total: Int): Unit = TODO("WP2")
}

/** A media library to browse, search and play from (mirrors `use-media-browser.ts`). */
class MediaBrowserControl(val handle: EntityHandle<MediaPlayerEntity>, val scope: Scope) {
    val state: Store<BrowserState> = Store(BrowserState())
    val arrival: RowsArrival = RowsArrival(scope)

    fun openTab(index: Int): Unit = TODO("WP2")

    /** Opens an expandable item below the current level. */
    fun open(item: BrowseItem): Unit = TODO("WP2")

    /** Goes up one level; false when already at a tab's top. */
    fun back(): Boolean = TODO("WP2")

    /** Searches the library (null or blank clears the search). Results are ranked as on the web. */
    fun search(text: String?): Unit = TODO("WP2")

    fun play(item: BrowseItem, mode: PlayMode = PlayMode.Play): Unit = TODO("WP2")
}

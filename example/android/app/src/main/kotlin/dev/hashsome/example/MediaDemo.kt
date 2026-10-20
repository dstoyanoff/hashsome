package dev.hashsome.example

import dev.hashsome.client.EntityRef
import dev.hashsome.ui.Align
import dev.hashsome.ui.Dashboard
import dev.hashsome.ui.Motion
import dev.hashsome.ui.PageScope
import dev.hashsome.ui.RowSpan
import dev.hashsome.ui.ShellScope
import dev.hashsome.ui.icons.Ui

/**
 * Every media component in every state the mock backend (`pnpm --filter @hashsome/example dev`) can produce: a player that plays
 * (`ma:living_room`), one that is paused and muted (`ma:kitchen`), one that is idle (`ma:office`), an unavailable one (`ma:garage`) and one
 * that does not exist. Nothing here sends a command to a real player: the server is given by `--es server <host:port>`.
 */
object MediaDemo : Dashboard("media", motion = Motion.Light, idleReturn = false) {
    private const val LIVING = "ma:living_room"
    private const val KITCHEN = "ma:kitchen"
    private const val OFFICE = "ma:office"
    private const val GARAGE = "ma:garage"
    private const val NOWHERE = "ma:nowhere"
    private val SPEAKERS = listOf(EntityRef(KITCHEN), EntityRef(OFFICE), EntityRef(GARAGE))

    override val pages = listOf(
        page("", "Bars", Ui.music) { bars() },
        page("states", "States", Ui.speaker) { states() },
        page("columns", "Columns", Ui.library) { columns() },
        page("board", "Board", Ui.house) { board() },
        page("full", "Full", Ui.listMusic) { mediaPlayerFull(LIVING, "Living room", SPEAKERS) },
        page("lists", "Lists", Ui.list) { lists() },
    )

    override fun shell(s: ShellScope) {
        s.navRail(pages)
    }

    private fun PageScope.bars() {
        column(gap = 3, grow = true) {
            mediaPlayerBar(LIVING, "Living room", rows = 1, speakers = SPEAKERS)
            // How a small display sets it up.
            mediaPlayerBar(KITCHEN, "Kitchen", rows = 1, time = false, shuffle = false, browse = false, grouping = false)
            mediaPlayerBar(LIVING, "Living room", rows = 2, speakers = SPEAKERS)
            spacer(grow = true)
        }
    }

    private fun PageScope.states() {
        column(gap = 3, grow = true) {
            mediaPlayerBar(OFFICE, "Office", rows = 1)
            mediaPlayerBar(GARAGE, "Garage", rows = 1)
            mediaPlayerBar(NOWHERE, "Nowhere", rows = 1)
            mediaPlayerBar(KITCHEN, "Kitchen", rows = 2)
            spacer(grow = true)
        }
    }

    private fun PageScope.columns() {
        row(gap = 3, grow = true, align = Align.Stretch) {
            mediaPlayerColumn(LIVING, "Living room", overlays = true, speakers = SPEAKERS, screen = true)
            mediaPlayerColumn(OFFICE, "Office", overlays = true, speakers = SPEAKERS)
            mediaPlayerColumn(GARAGE, "Garage", overlays = true)
        }
    }

    /** The bathroom's home page: the room beside the player, which opens its library as a panel over the page. */
    private fun PageScope.board() {
        board {
            cell(cols = 7) {
                mediaPlayerBar(KITCHEN, "Kitchen", rows = 1, time = false, shuffle = false, browse = false, grouping = false)
            }
            cell(cols = 5, rows = RowSpan.Fill) {
                // Without panels of its own for the library and the queue: one key opens the whole player.
                mediaPlayerColumn(KITCHEN, "Kitchen", overlays = false, speakers = SPEAKERS)
            }
        }
    }

    private fun PageScope.lists() {
        row(gap = 5, grow = true, align = Align.Stretch) {
            mediaBrowser(LIVING)
            mediaQueue(LIVING)
            mediaSpeakers(LIVING, SPEAKERS)
        }
    }
}

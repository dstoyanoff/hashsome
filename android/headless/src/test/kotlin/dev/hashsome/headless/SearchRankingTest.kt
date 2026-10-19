package dev.hashsome.headless

import org.junit.Assert.assertEquals
import org.junit.Test

// __tests__/search-ranking.test.ts
class SearchRankingTest {
    private fun titles(items: List<dev.hashsome.client.BrowseItem>) = items.map { it.title }

    @Test
    fun `an exact match comes first, then titles that start with it, then a word, then anywhere`() {
        val ranked = rankSearchResults(
            listOf(
                item("1", "Chill Music Mix"),
                item("2", "Greatest Cdeep Music Hits"),
                item("3", "Cdeep Music Radio"),
                item("4", "Cdeep Music"),
                item("5", "The Best Of"),
            ),
            "cdeep music",
        )

        assertEquals(
            listOf("Cdeep Music", "Cdeep Music Radio", "Greatest Cdeep Music Hits", "Chill Music Mix", "The Best Of"),
            titles(ranked),
        )
    }

    @Test
    fun `matching ignores case and keeps the library order among equal matches`() {
        val ranked = rankSearchResults(listOf(item("a", "Zeta"), item("b", "Alpha"), item("c", "ALPHA")), "alpha")
        assertEquals(listOf("b", "c", "a"), ranked.map { it.id })
    }

    @Test
    fun `a word start counts after any of the separators`() {
        val ranked = rankSearchResults(
            listOf(item("x", "Live (Blue Monday)"), item("y", "Bluebird"), item("z", "True-Blue")),
            "blue",
        )

        // Bluebird starts with it (1); the other two have a word that does (2), in library order.
        assertEquals(listOf("y", "x", "z"), ranked.map { it.id })
    }

    @Test
    fun `no results is no results`() {
        assertEquals(emptyList<dev.hashsome.client.BrowseItem>(), rankSearchResults(emptyList(), "x"))
    }
}

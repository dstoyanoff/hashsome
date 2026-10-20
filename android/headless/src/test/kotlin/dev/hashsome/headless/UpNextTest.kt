package dev.hashsome.headless

import dev.hashsome.client.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Test

class UpNextTest {
    private fun queue(current: String?, vararg ids: String) = QueueState(items = ids.map { QueueItem(it, "Track $it", current = it == current) }, loading = false)

    @Test
    fun `up next is what follows the track playing, up to the count`() {
        val state = queue("c", "a", "b", "c", "d", "e", "f")
        assertEquals(listOf("d", "e"), state.upNext(2).map { it.id })
        assertEquals(listOf("d", "e", "f"), state.upNext(10).map { it.id })
    }

    @Test
    fun `nothing follows the last track`() {
        assertEquals(emptyList<String>(), queue("c", "a", "b", "c").upNext(3).map { it.id })
    }

    @Test
    fun `a queue with no current track offers its first items`() {
        assertEquals(listOf("a", "b"), queue(null, "a", "b", "c").upNext(2).map { it.id })
    }

    @Test
    fun `a count below one is nothing`() {
        assertEquals(emptyList<String>(), queue("a", "a", "b").upNext(0).map { it.id })
        assertEquals(emptyList<String>(), queue("a", "a", "b").upNext(-1).map { it.id })
    }
}

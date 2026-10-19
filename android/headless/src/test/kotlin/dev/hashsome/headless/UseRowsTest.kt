package dev.hashsome.headless

import org.junit.Assert.assertEquals
import org.junit.Test

// __tests__/use-rows.test.tsx: the schedule of a list that opens.
class UseRowsTest {
    private val rig = Rig()
    private val arrival = RowsArrival(rig.scope)

    private fun visibleAfter(ms: Long): Int {
        rig.scheduler.advance(ms)
        return arrival.visible.value
    }

    @Test
    fun `a long list opens with one row and grows a row every 55 ms up to 10`() {
        arrival.start(60)
        assertEquals(1, arrival.visible.value)
        assertEquals(1, visibleAfter(54))
        assertEquals(2, visibleAfter(1))
        assertEquals(3, visibleAfter(55))
        assertEquals(10, visibleAfter(55 * 7))
        assertEquals(10, visibleAfter(10_000))
        assertEquals(55L, RowsArrival.STEP_MS)
        assertEquals(10, RowsArrival.FIRST_ROWS)
    }

    @Test
    fun `the growth is one row per step, never a screenful`() {
        arrival.start(60)
        val seen = ArrayList<Int>()
        arrival.visible.listen { seen += it }
        rig.scheduler.advance(2_000)
        assertEquals((2..10).toList(), seen)
    }

    @Test
    fun `a list that ends sooner stops there`() {
        arrival.start(6)
        assertEquals(1, arrival.visible.value)
        assertEquals(6, visibleAfter(10_000))
    }

    @Test
    fun `a short list is drawn whole`() {
        arrival.start(4)
        assertEquals(4, arrival.visible.value)
        arrival.start(0)
        assertEquals(0, arrival.visible.value)
        arrival.start(5)
        assertEquals(1, arrival.visible.value)
    }

    @Test
    fun `ten more come when the end is near, as far as the list goes`() {
        arrival.start(60)
        rig.scheduler.advance(1_000)
        arrival.more()
        assertEquals(20, arrival.visible.value)
        arrival.more()
        arrival.more()
        arrival.more()
        arrival.more()
        assertEquals(60, arrival.visible.value)
        arrival.more()
        assertEquals(60, arrival.visible.value)
    }

    @Test
    fun `more during the opening does not let the opening take rows back`() {
        arrival.start(60)
        arrival.more()
        assertEquals(11, arrival.visible.value)
        rig.scheduler.advance(2_000)
        assertEquals(11, arrival.visible.value)
    }

    @Test
    fun `another list starts over`() {
        arrival.start(60)
        rig.scheduler.advance(2_000)
        arrival.start(30)
        assertEquals(1, arrival.visible.value)
        assertEquals(2, visibleAfter(55))
    }

    @Test
    fun `nothing arrives after the scope is disposed`() {
        arrival.start(60)
        rig.scope.dispose()
        assertEquals(1, visibleAfter(5_000))
        assertEquals(0, rig.scheduler.pending)
    }
}

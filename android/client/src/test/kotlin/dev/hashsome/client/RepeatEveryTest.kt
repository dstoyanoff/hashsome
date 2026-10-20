package dev.hashsome.client

import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatEveryTest {
    @Test
    fun `repeats until cancelled`() {
        val s = TestScheduler()
        var n = 0
        val cancel = repeatEvery(50, s::after) { n++ }
        s.advance(149)
        assertEquals(2, n)
        s.advance(1)
        assertEquals(3, n)
        cancel()
        s.advance(500)
        assertEquals(3, n)
        assertEquals(0, s.pending)
    }

    @Test
    fun `cancelling from inside the callback stops the loop`() {
        val s = TestScheduler()
        var n = 0
        var cancel: Cancel? = null
        cancel = repeatEvery(10, s::after) {
            n++
            if (n == 3) cancel?.invoke()
        }
        s.advance(1000)
        assertEquals(3, n)
        assertEquals(0, s.pending)
    }

    @Test
    fun `the next run is posted after the callback returns`() {
        val s = TestScheduler()
        val times = ArrayList<Long>()
        // The callback schedules something of its own at +5: the next tick, posted after it returns, still comes 10 ms from now.
        repeatEvery(10, s::after) {
            times += s.now()
            s.after(5) { times += -s.now() }
        }
        s.advance(25)
        assertEquals(listOf(10L, -15L, 20L, -25L), times)
    }
}

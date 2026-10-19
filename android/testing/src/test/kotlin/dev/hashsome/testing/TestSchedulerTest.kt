package dev.hashsome.testing

import dev.hashsome.client.Cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TestSchedulerTest {
    @Test
    fun `post waits for the scheduler to be driven`() {
        val s = TestScheduler()
        val ran = ArrayList<String>()
        s.post { ran += "a" }
        assertEquals(emptyList<String>(), ran)
        s.runCurrent()
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `after runs once at its time, in time order`() {
        val s = TestScheduler()
        val ran = ArrayList<String>()
        s.after(200) { ran += "late" }
        s.after(100) { ran += "early" }
        s.advance(99)
        assertEquals(emptyList<String>(), ran)
        s.advance(1)
        assertEquals(listOf("early"), ran)
        s.advance(1000)
        assertEquals(listOf("early", "late"), ran)
    }

    @Test
    fun `now is the task's own time while it runs`() {
        val s = TestScheduler()
        val seen = ArrayList<Long>()
        s.after(30) { seen += s.now() }
        s.after(70) { seen += s.now() }
        s.advance(100)
        assertEquals(listOf(30L, 70L), seen)
        assertEquals(100L, s.now())
    }

    @Test
    fun `every repeats until cancelled`() {
        val s = TestScheduler()
        var n = 0
        val cancel = s.every(55) { n++ }
        s.advance(55 * 3)
        assertEquals(3, n)
        cancel()
        s.advance(1000)
        assertEquals(3, n)
        assertEquals(0, s.pending)
    }

    @Test
    fun `a cancelled after never runs`() {
        val s = TestScheduler()
        var ran = false
        val cancel = s.after(10) { ran = true }
        cancel()
        s.advance(50)
        assertEquals(false, ran)
    }

    @Test
    fun `a task scheduled by a task inside the window runs in the same advance`() {
        val s = TestScheduler()
        val ran = ArrayList<Long>()
        s.after(10) { s.after(10) { ran += s.now() } }
        s.advance(25)
        assertEquals(listOf(20L), ran)
    }

    @Test
    fun `tasks due together run in the order they were scheduled`() {
        val s = TestScheduler()
        val ran = ArrayList<Int>()
        s.after(5) { ran += 1 }
        s.after(5) { ran += 2 }
        s.post { ran += 0 }
        s.advance(5)
        assertEquals(listOf(0, 1, 2), ran)
    }

    @Test
    fun `every cancelled inside its own callback stops`() {
        val s = TestScheduler()
        var n = 0
        var cancel: Cancel? = null
        cancel = s.every(10) {
            n++
            if (n == 2) cancel?.invoke()
        }
        s.advance(100)
        assertEquals(2, n)
        assertEquals(0, s.pending)
    }

    @Test
    fun `after zero and post from inside a task run in the same advance`() {
        val s = TestScheduler()
        val ran = ArrayList<String>()
        s.after(5) {
            s.after(0) { ran += "after0@" + s.now() }
            s.post { ran += "post@" + s.now() }
        }
        s.advance(5)
        assertEquals(listOf("after0@5", "post@5"), ran)
    }

    @Test
    fun `a task can cancel a later task due at the same time`() {
        val s = TestScheduler()
        var ran = false
        var cancel: Cancel? = null
        s.after(5) { cancel?.invoke() }
        cancel = s.after(5) { ran = true }
        s.advance(5)
        assertEquals(false, ran)
    }

    @Test
    fun `advance is not re-entrant`() {
        val s = TestScheduler()
        var failure: Throwable? = null
        s.after(5) {
            try {
                s.advance(1)
            } catch (e: IllegalStateException) {
                failure = e
            }
        }
        s.advance(10)
        assertEquals(true, failure != null)
        assertEquals(10L, s.now())
    }

    @Test
    fun `a task that throws leaves the rest queued for the next advance`() {
        val s = TestScheduler()
        val ran = ArrayList<String>()
        s.after(5) { throw IllegalStateException("boom") }
        s.after(8) { ran += "later" }
        try {
            s.advance(10)
            fail("the exception should reach the test")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(5L, s.now())
        assertEquals(emptyList<String>(), ran)
        s.advance(10)
        assertEquals(listOf("later"), ran)
    }

    @Test
    fun `wall time starts where the test says and moves with advance`() {
        val s = TestScheduler()
        s.wall = 1_000_000L
        val seen = ArrayList<Long>()
        s.after(30) { seen += s.wallMs() }
        s.advance(100)
        assertEquals(listOf(1_000_030L), seen)
        assertEquals(1_000_100L, s.wallMs())
    }
}

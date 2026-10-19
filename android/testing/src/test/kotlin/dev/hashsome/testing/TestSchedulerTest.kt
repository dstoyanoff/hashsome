package dev.hashsome.testing

import org.junit.Assert.assertEquals
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
}

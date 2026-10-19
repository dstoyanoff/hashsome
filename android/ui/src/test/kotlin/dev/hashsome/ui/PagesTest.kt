package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesTest {
    private fun node(kind: PartKind, vararg children: PartNode) = PartNode(kind, children.toList())

    @Test
    fun `a top bar is a top row`() {
        assertTrue(isTopRow(node(PartKind.TopBar)))
    }

    @Test
    fun `a row with a chip, or with a row that has one, is a top row`() {
        assertTrue(isTopRow(node(PartKind.Row, node(PartKind.Other), node(PartKind.Chip))))
        assertTrue(isTopRow(node(PartKind.Row, node(PartKind.Row, node(PartKind.Chip)))))
    }

    @Test
    fun `other things are not, nor is a column that holds a clock somewhere`() {
        assertFalse(isTopRow(null as PartNode?))
        assertFalse(isTopRow(node(PartKind.Other)))
        assertFalse(isTopRow(node(PartKind.Chip)))
        assertFalse(isTopRow(node(PartKind.Row, node(PartKind.Other))))
        assertFalse(isTopRow(node(PartKind.Other, node(PartKind.Row, node(PartKind.Chip)))))
    }

    @Test
    fun `a pinned header sits where an unpinned page's would, and the content starts under it`() {
        // 12 dp of space (18 px), the centering's top offset 3 px, a 60 px header and a gap of 18 px.
        assertEquals(21, PinnedMath.headerTop(18, 3))
        assertEquals(99, PinnedMath.contentTop(18, 3, 60, 18))
        // Without any centering the content starts where the header's bottom plus the gap is.
        assertEquals(PinnedMath.headerTop(18, 0) + 60 + 18, PinnedMath.contentTop(18, 0, 60, 18))
    }

    @Test
    fun `tabs share the width with a gap between them`() {
        assertEquals(100f, ListTabsMath.tabWidth(210f, 2, 10f), 0.001f)
        assertEquals(0f, ListTabsMath.tabLeft(0, 210f, 2, 10f), 0.001f)
        assertEquals(110f, ListTabsMath.tabLeft(1, 210f, 2, 10f), 0.001f)
        assertEquals(300f, ListTabsMath.tabWidth(300f, 1, 10f), 0.001f)
    }

    @Test
    fun `a touch hits the tab it is on, the gap belonging half to each side`() {
        assertEquals(0, ListTabsMath.hit(50f, 210f, 2, 10f))
        assertEquals(1, ListTabsMath.hit(160f, 210f, 2, 10f))
        // The gap is 100..110: its first half is the first tab's, its second half the second's.
        assertEquals(0, ListTabsMath.hit(104f, 210f, 2, 10f))
        assertEquals(1, ListTabsMath.hit(106f, 210f, 2, 10f))
        assertEquals(-1, ListTabsMath.hit(-20f, 210f, 2, 10f))
        assertEquals(-1, ListTabsMath.hit(300f, 210f, 2, 10f))
    }
}

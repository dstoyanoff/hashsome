package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardSwitchTest {
    private class Fake(id: String) : Dashboard(id) {
        override val pages: List<PageSpec> = emptyList()
    }

    private val a = Fake("a")
    private val b = Fake("b")
    private val all = listOf(a, b)
    private val events = ArrayList<String>()
    private val errors = ArrayList<String>()
    private val settings = InMemoryHostSettings()

    /** Stands for `DashboardHost`: making one shows its first page, which the real host reports to the `PageListener`. */
    private val pageListener = object : PageListener {
        override fun onPage(dashboardId: String, path: String) {
            events += "page $dashboardId/$path"
        }
    }

    private fun switch() = DashboardSwitch<String>(
        choose = { pickDashboard(all, settings.dashboard.value) { errors += it } },
        make = { events += "make ${it.id}"; pageListener.onPage(it.id, ""); it.id },
        drop = { events += "drop $it" },
        show = { events += "show $it" },
    ).also { it.start(settings.dashboard) }

    @Test
    fun `no setting shows the first dashboard`() {
        switch()
        assertEquals(listOf("make a", "page a/", "show a"), events)
    }

    @Test
    fun `a changed setting drops the old dashboard and shows the other one's first page`() {
        switch()
        events.clear()
        settings.dashboard.set("b")
        assertEquals(listOf("drop a", "make b", "page b/", "show b"), events)
        events.clear()
        settings.dashboard.set("a")
        assertEquals(listOf("drop b", "make a", "page a/", "show a"), events)
    }

    @Test
    fun `the same id again, or null for the first, does nothing`() {
        settings.dashboard.set("b")
        switch()
        events.clear()
        settings.dashboard.set("b")
        assertEquals(emptyList<String>(), events)
        settings.dashboard.set("a")
        events.clear()
        settings.dashboard.set(null)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `an unknown id shows the first dashboard and is reported`() {
        settings.dashboard.set("b")
        switch()
        events.clear()
        settings.dashboard.set("nope")
        assertEquals(listOf("drop b", "make a", "page a/", "show a"), events)
        assertEquals(1, errors.size)
        // Unknown again while the first is showing: still the first, no rebuild.
        events.clear()
        settings.dashboard.set("also-nope")
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `stop drops the dashboard and stops following the setting`() {
        val s = switch()
        events.clear()
        s.stop()
        settings.dashboard.set("b")
        assertEquals(listOf("drop a"), events)
        assertNull(pickDashboard(emptyList(), "x") {})
    }
}

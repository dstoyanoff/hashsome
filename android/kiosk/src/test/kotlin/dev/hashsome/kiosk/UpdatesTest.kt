package dev.hashsome.kiosk

import dev.hashsome.client.Store
import dev.hashsome.testing.TestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private val sha = "a".repeat(64)

    private fun feed(code: Int = 1903, pkg: String = "dev.hashsome.home", apk: String = "dev.hashsome.home-$code.apk", hash: String = sha) =
        """{"packageName":"$pkg","versionName":"0.19.3","versionCode":$code,"apk":"$apk","sha256":"$hash","size":1000,"notes":"Fixes"}"""

    @Test
    fun `latest json lives in the feed's folder, whatever the base looks like`() {
        assertEquals("http://ha.lan:8123/local/hashsome/latest.json", ReleaseFeed.latestUrl("http://ha.lan:8123/local/hashsome"))
        assertEquals("http://ha.lan:8123/local/hashsome/latest.json", ReleaseFeed.latestUrl("http://ha.lan:8123/local/hashsome/ "))
        assertEquals("http://ha.lan:8123/local/hashsome/latest.json", ReleaseFeed.latestUrl("http://ha.lan:8123/local/hashsome/latest.json"))
        assertEquals("http://ha.lan:8123/local/hashsome", ReleaseFeed.folder("http://ha.lan:8123/local/hashsome/latest.json"))
    }

    @Test
    fun `a release is read from latest json, the apk relative to the folder or absolute`() {
        val r = ReleaseFeed.parse(feed(), "http://h/local/x")!!
        assertEquals("http://h/local/x/dev.hashsome.home-1903.apk", r.apkUrl)
        assertEquals(1903L, r.versionCode)
        assertEquals("0.19.3", r.versionName)
        assertEquals("Fixes", r.notes)
        assertEquals("https://cdn/app.apk", ReleaseFeed.parse(feed(apk = "https://cdn/app.apk"), "http://h/local/x")!!.apkUrl)
    }

    @Test
    fun `a latest json that is not a release is nothing`() {
        assertNull(ReleaseFeed.parse("not json", "http://h"))
        assertNull(ReleaseFeed.parse("""{"versionCode":0,"apk":"a.apk","sha256":"$sha"}""", "http://h"))
        assertNull(ReleaseFeed.parse(feed(hash = "zz"), "http://h"))
        assertNull(ReleaseFeed.parse("""{"versionCode":5,"sha256":"$sha"}""", "http://h"))
    }

    @Test
    fun `only a higher code for this package is newer`() {
        val r = ReleaseFeed.parse(feed(code = 1903), "http://h")!!
        assertTrue(ReleaseFeed.isNewer(r, "dev.hashsome.home", 1902))
        assertTrue(!ReleaseFeed.isNewer(r, "dev.hashsome.home", 1903))
        assertTrue(!ReleaseFeed.isNewer(r, "dev.hashsome.home", 2000))
        assertTrue(!ReleaseFeed.isNewer(r, "com.other", 1))
    }

    private class FakeIO(var latest: String = "", var fileSha: String = "") : UpdateIO {
        val installed = ArrayList<String>()
        val deleted = ArrayList<String>()
        var failFetch = false
        var fileSize = 1000L
        override fun fetch(url: String): String = if (failFetch) error("no route") else latest
        override fun download(url: String, progress: (Int) -> Unit): String {
            progress(40)
            progress(100)
            return "/cache/app.apk"
        }
        override fun sha256(path: String) = fileSha
        override fun size(path: String) = fileSize
        override fun delete(path: String) {
            deleted += path
        }
        override fun install(path: String) {
            installed += path
        }
    }

    private val sched = TestScheduler()
    private val url = Store<String?>(null)
    private val io = FakeIO()

    private fun updater(installedCode: Long = 1902) = Updater(
        url, io, sched, { it() }, "dev.hashsome.home", installedCode, "0.19.2", clock = { 5_000L }, checkEveryMs = 3_600_000, firstCheckMs = 1_000,
    )

    @Test
    fun `without a feed nothing is checked`() {
        val u = updater().also { it.start() }
        sched.advance(10_000_000)
        assertEquals(UpdateState.Off, u.state.value)
    }

    @Test
    fun `a feed is checked a minute after start and then on its schedule`() {
        io.latest = feed(code = 1903)
        url.set("http://h/local/x")
        val u = updater().also { it.start() }
        assertEquals(UpdateState.Current(null), u.state.value)
        sched.advance(1_000)
        assertTrue(u.state.value is UpdateState.Available)
        io.latest = feed(code = 1902)
        sched.advance(3_600_000)
        assertEquals(UpdateState.Current(5_000L), u.state.value)
    }

    @Test
    fun `a check that fails says so and the next one can recover`() {
        url.set("http://h/local/x")
        io.failFetch = true
        val u = updater().also { it.start() }
        sched.advance(1_000)
        assertTrue(u.state.value is UpdateState.Failed)
        io.failFetch = false
        io.latest = feed(code = 1903)
        u.check()
        sched.advance(0)
        assertTrue(u.state.value is UpdateState.Available)
    }

    @Test
    fun `install downloads, checks the checksum and hands the apk to the installer`() {
        url.set("http://h/local/x")
        io.latest = feed(code = 1903)
        io.fileSha = sha
        val u = updater().also { it.start() }
        u.check()
        sched.advance(0)
        u.install()
        sched.advance(0)
        assertEquals(listOf("/cache/app.apk"), io.installed)
        assertTrue(u.state.value is UpdateState.Installing)
    }

    @Test
    fun `a download that does not match its checksum is deleted and never installed`() {
        url.set("http://h/local/x")
        io.latest = feed(code = 1903)
        io.fileSha = "b".repeat(64)
        val u = updater().also { it.start() }
        u.check()
        sched.advance(0)
        u.install()
        sched.advance(0)
        assertTrue(io.installed.isEmpty())
        assertEquals(listOf("/cache/app.apk"), io.deleted)
        val failed = u.state.value as UpdateState.Failed
        assertTrue(failed.message.contains("checksum"))
        assertEquals(1903L, failed.release?.versionCode)
    }

    @Test
    fun `a wrong size is not installed either, and install does nothing when nothing is available`() {
        url.set("http://h/local/x")
        io.latest = feed(code = 1903)
        io.fileSha = sha
        io.fileSize = 5
        val u = updater().also { it.start() }
        u.install()
        sched.advance(0)
        assertTrue(io.installed.isEmpty())
        u.check()
        sched.advance(0)
        u.install()
        sched.advance(0)
        assertTrue(u.state.value is UpdateState.Failed)
        assertTrue(io.installed.isEmpty())
    }

    @Test
    fun `clearing the feed stops the checks`() {
        url.set("http://h/local/x")
        io.latest = feed(code = 1903)
        val u = updater().also { it.start() }
        url.set(null)
        assertEquals(UpdateState.Off, u.state.value)
        sched.advance(10_000_000)
        assertEquals(UpdateState.Off, u.state.value)
    }
}

package dev.hashsome.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagesTest {
    private val server = ServerAddress("http://10.1.1.5:3000")

    @Test
    fun `a runtime asset gets the server's origin and the size`() {
        assertEquals(
            "http://10.1.1.5:3000/_hashsome/asset/ma?path=%2Fimageproxy%2Fa.jpg&size=small",
            Images.url(server, "/_hashsome/asset/ma?path=%2Fimageproxy%2Fa.jpg", AssetSize.Small),
        )
        assertEquals("&size=large", Images.url(server, "/_hashsome/asset/ha?path=x", AssetSize.Large).substring("http://10.1.1.5:3000/_hashsome/asset/ha?path=x".length))
    }

    @Test
    fun `a size already asked for is replaced, as sizedAssetUrl does`() {
        assertEquals("http://10.1.1.5:3000/_hashsome/asset/ma?path=x&size=large", Images.url(server, "/_hashsome/asset/ma?path=x&size=small", AssetSize.Large))
        assertEquals("http://10.1.1.5:3000/_hashsome/asset/ma?path=x&other=1&size=medium", Images.url(server, "/_hashsome/asset/ma?path=x&size=small&other=1", AssetSize.Medium))
        // Only the first is taken out, like the one-shot regex in TypeScript.
        assertEquals("http://10.1.1.5:3000/_hashsome/asset/ma?path=x&size=large&size=medium", Images.url(server, "/_hashsome/asset/ma?path=x&size=small&size=large", AssetSize.Medium))
    }

    @Test
    fun `the origin's trailing slash does not double`() {
        assertEquals("https://home.lan/_hashsome/asset/ma?path=x&size=medium", Images.url(ServerAddress("https://home.lan/"), "/_hashsome/asset/ma?path=x", AssetSize.Medium))
    }

    @Test
    fun `anything else comes back unchanged`() {
        for (url in listOf("https://picsum.photos/seed/aurora/400", "/other/path?x=1", "/_hashsome/assets?x=1", "/_hashsome/asset", "", "http://10.1.1.5:3000/_hashsome/asset/ma?path=x")) {
            assertEquals(url, Images.url(server, url, AssetSize.Large))
        }
    }

    @Test
    fun `pictures are decoded at the largest reduction that still fills the target`() {
        assertEquals(1, Images.sampleSize(400, 400))
        assertEquals(1, Images.sampleSize(500, 400))
        assertEquals(2, Images.sampleSize(800, 400))
        assertEquals(2, Images.sampleSize(1000, 400))
        assertEquals(4, Images.sampleSize(1600, 400))
        assertEquals(1, Images.sampleSize(100, 400))
        assertEquals(1, Images.sampleSize(1600, 0))
    }

    private class Fetches {
        val calls = ArrayList<String>()
        val queued = ArrayList<() -> Unit>()
        val posted = ArrayList<() -> Unit>()
        var now = 0L

        fun install() {
            Images.reset()
            Images.fetcher = { url, _ ->
                calls += url
                null
            }
            Images.execute = { queued += it }
            Images.post = { posted += it }
            Images.clock = { now }
        }

        fun finish() {
            while (queued.isNotEmpty()) queued.removeAt(0)()
            while (posted.isNotEmpty()) posted.removeAt(0)()
        }
    }

    @Test
    fun `loads of one picture share one fetch and each caller is told`() {
        val f = Fetches().also { it.install() }
        val told = ArrayList<String>()
        Images.load("u", 100) { told += "a" }
        Images.load("u", 100) { told += "b" }
        val dropped = Images.load("u", 100) { told += "c" }
        Images.load("u", 200) { told += "d" }
        dropped()
        f.finish()
        assertEquals(listOf("u", "u"), f.calls)
        assertEquals(listOf("a", "b", "d"), told)
    }

    @Test
    fun `a url that failed is not asked for again for thirty seconds`() {
        val f = Fetches().also { it.install() }
        val told = ArrayList<String>()
        Images.load("u", 100) { told += "first" }
        f.finish()
        f.now = 29_999
        Images.load("u", 100) { told += "second" }
        assertTrue("answered through the main thread, not inside load()", told == listOf("first"))
        f.finish()
        assertEquals(listOf("first", "second"), told)
        assertEquals(1, f.calls.size)
        f.now = 30_000
        Images.load("u", 100) { told += "third" }
        f.finish()
        assertEquals(2, f.calls.size)
        assertEquals(3, told.size)
    }

    @Test
    fun `a body over the limit is refused`() {
        assertEquals(5, Images.readCapped(java.io.ByteArrayInputStream(ByteArray(5)), limit = 5)!!.size)
        assertEquals(null, Images.readCapped(java.io.ByteArrayInputStream(ByteArray(6)), limit = 5))
        assertEquals(40_000, Images.readCapped(java.io.ByteArrayInputStream(ByteArray(40_000)), limit = 50_000)!!.size)
    }
}

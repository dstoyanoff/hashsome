package dev.hashsome.client

import org.junit.Assert.assertEquals
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
}

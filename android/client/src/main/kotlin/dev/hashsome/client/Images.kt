package dev.hashsome.client

import android.graphics.Bitmap

/** Artwork and pictures, from the runtime's asset endpoint. */
object Images {
    /**
     * The address to load [artworkUrl] from, asking for it at [size]: the same semantics as `sizedAssetUrl`
     * in packages/core/src/protocol.ts. A runtime path (`/_hashsome/asset/...`) gets `&size=` and the server's
     * origin; anything else (a streaming service's own address) comes back unchanged.
     */
    fun url(server: ServerAddress, artworkUrl: String, size: AssetSize): String = TODO("WP1")

    /**
     * Loads [url] decoded to about [targetPx] on its longer side, off the main thread, and calls [done] on it
     * (null when it fails). Decoded bitmaps are cached in memory (12 MB LRU). Cancelling stops [done].
     */
    fun load(url: String, targetPx: Int, done: (Bitmap?) -> Unit): Cancel = TODO("WP1")
}

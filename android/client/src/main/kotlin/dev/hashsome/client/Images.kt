package dev.hashsome.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Artwork and pictures, from the runtime's asset endpoint. */
object Images {
    // Everything that touches `android.*` is lazy, so `url` works (and is tested) on a plain JVM.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pool by lazy { Executors.newFixedThreadPool(3) { r -> Thread(r, "hashsome-images").also { it.isDaemon = true } } }
    private val cache by lazy {
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private val sizeParam = Regex("&size=[a-z]+")

    /** How a result reaches the caller: the main thread. A seam for tests. */
    internal var post: (() -> Unit) -> Unit = { fn -> main.post(fn) }

    /**
     * The address to load [artworkUrl] from, asking for it at [size]: the same semantics as `sizedAssetUrl`
     * in packages/core/src/protocol.ts. A runtime path (`/_hashsome/asset/...`) gets `&size=` and the server's
     * origin; anything else (a streaming service's own address) comes back unchanged.
     */
    fun url(server: ServerAddress, artworkUrl: String, size: AssetSize): String {
        if (!artworkUrl.startsWith("${ProtocolRules.ASSET_PATH}/")) return artworkUrl
        return server.baseUrl.trimEnd('/') + sizeParam.replaceFirst(artworkUrl, "") + "&size=" + size.wire
    }

    /**
     * Loads [url] decoded to about [targetPx] on its longer side, off the main thread, and calls [done] on it
     * (null when it fails). Decoded bitmaps are cached in memory (12 MB LRU). Cancelling stops [done].
     * A picture already in the cache is delivered at once, before this returns.
     */
    fun load(url: String, targetPx: Int, done: (Bitmap?) -> Unit): Cancel {
        val cancelled = AtomicBoolean(false)
        val key = "$url@$targetPx"
        val hit = cache.get(key)
        if (hit != null) {
            done(hit)
            return {}
        }
        pool.execute {
            val bitmap = if (cancelled.get()) null else fetch(url, targetPx)
            if (bitmap != null) cache.put(key, bitmap)
            post { if (!cancelled.get()) done(bitmap) }
        }
        return { cancelled.set(true) }
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), targetPx) })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** The largest power of two that still leaves [longSide] at [targetPx] or more. */
    internal fun sampleSize(longSide: Int, targetPx: Int): Int {
        var sample = 1
        while (targetPx > 0 && longSide / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}

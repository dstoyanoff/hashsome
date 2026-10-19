package dev.hashsome.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Artwork and pictures, from the runtime's asset endpoint. */
object Images {
    // Everything that touches `android.*` is lazy, so `url` works (and is tested) on a plain JVM.
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val pool by lazy { Executors.newFixedThreadPool(3) { r -> Thread(r, "hashsome-images").also { it.isDaemon = true } } }
    private val cache by lazy {
        object : LruCache<String, Bitmap>(CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }

    private const val CACHE_BYTES = 12 * 1024 * 1024
    private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    private const val FAILURE_MEMORY_MS = 30_000L
    private val sizeParam = Regex("&size=[a-z]+")

    /** How a result reaches the caller: the main thread. A seam for tests. */
    internal var post: (() -> Unit) -> Unit = { fn -> main.post { fn() } }

    /** Runs a fetch off the main thread. A seam for tests. */
    internal var execute: (() -> Unit) -> Unit = { fn -> pool.execute(fn) }

    /** Fetches and decodes one picture, or null. A seam for tests. */
    internal var fetcher: (String, Int) -> Bitmap? = ::fetch

    /** Milliseconds on a monotonic clock. A seam for tests. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** Callers waiting for a key that is being fetched, and when each failed key failed. Main thread only. */
    private val inFlight = HashMap<String, MutableList<Waiter>>()
    private val failedAt = HashMap<String, Long>()

    private class Waiter(val done: (Bitmap?) -> Unit) {
        var cancelled = false
    }

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
     * A picture already in the cache is delivered at once, before this returns. Loads of the same picture at the same size
     * share one fetch; a url that failed is not asked for again for 30 s (its callers get null, through the main thread).
     * Call it from the main thread.
     */
    fun load(url: String, targetPx: Int, done: (Bitmap?) -> Unit): Cancel {
        val key = "$url@$targetPx"
        val hit = cache.get(key)
        if (hit != null) {
            done(hit)
            return {}
        }
        val waiter = Waiter(done)
        val cancel: Cancel = { waiter.cancelled = true }
        val failed = failedAt[key]
        if (failed != null) {
            if (clock() - failed < FAILURE_MEMORY_MS) {
                post { if (!waiter.cancelled) done(null) }
                return cancel
            }
            failedAt.remove(key)
        }
        val waiting = inFlight[key]
        if (waiting != null) {
            waiting += waiter
            return cancel
        }
        inFlight[key] = arrayListOf(waiter)
        execute {
            val bitmap = fetcher(url, targetPx)
            if (bitmap != null) cache.put(key, bitmap)
            post {
                val all = inFlight.remove(key).orEmpty()
                if (bitmap == null) failedAt[key] = clock()
                for (w in all) if (!w.cancelled) w.done(bitmap)
            }
        }
        return cancel
    }

    /** Forgets what is in flight and what failed. For tests. */
    internal fun reset() {
        inFlight.clear()
        failedAt.clear()
    }

    private fun fetch(url: String, targetPx: Int): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = readCapped(connection.inputStream) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), targetPx) })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** The whole stream, or null when it is longer than [MAX_BODY_BYTES]. */
    internal fun readCapped(input: java.io.InputStream, limit: Int = MAX_BODY_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) return null
            out.write(buf, 0, n)
        }
    }

    /** The largest power of two that still leaves [longSide] at [targetPx] or more. */
    internal fun sampleSize(longSide: Int, targetPx: Int): Int {
        var sample = 1
        while (targetPx > 0 && longSide / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}

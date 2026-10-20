package dev.hashsome.ui

/**
 * The colors a picture stands for, to tint what is behind it: three, the most present vivid hues first, each made bright enough to show
 * and dark enough to carry white text over it. Pure arithmetic on ARGB pixels (no `android.graphics`), so it is tested on the JVM.
 */
internal object ArtworkPalette {
    private const val HUES = 12
    private const val MIN_SATURATION = 0.22f
    private const val MIN_VALUE = 0.18f

    /** Three ARGB colors for [pixels] (any size, a downscaled copy is plenty). A picture with no vivid color gives three greys. */
    fun from(pixels: IntArray): List<Int> {
        val weight = DoubleArray(HUES)
        val sumR = DoubleArray(HUES)
        val sumG = DoubleArray(HUES)
        val sumB = DoubleArray(HUES)
        var greyLevel = 0.0
        var greys = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            val hsv = hsvOf(r, g, b)
            if (hsv[1] < MIN_SATURATION || hsv[2] < MIN_VALUE) {
                greyLevel += hsv[2]
                greys++
                continue
            }
            val bucket = (hsv[0] / (360f / HUES)).toInt() % HUES
            // More vivid and brighter counts for more.
            val w = (hsv[1] * hsv[2]).toDouble()
            weight[bucket] += w
            sumR[bucket] += r * w
            sumG[bucket] += g * w
            sumB[bucket] += b * w
        }

        // The heaviest hues, not two next to each other (they are one color to the eye).
        val picked = ArrayList<Int>()
        for (bucket in weight.indices.sortedByDescending { weight[it] }) {
            if (weight[bucket] <= 0.0 || picked.size == 3) break
            if (picked.none { near(it, bucket) }) picked += bucket
        }

        if (picked.isEmpty()) {
            val level = if (greys == 0) 0.4f else (greyLevel / greys).toFloat()
            return listOf(grey(level * 0.9f + 0.2f), grey(level * 0.6f + 0.15f), grey(level * 0.4f + 0.1f))
        }

        val colors = picked.map { bucket ->
            val w = weight[bucket]
            fit(Math.round(sumR[bucket] / w).toInt(), Math.round(sumG[bucket] / w).toInt(), Math.round(sumB[bucket] / w).toInt())
        }.toMutableList()
        // A picture with one or two hues gets the rest as neighbours of them, so the backdrop still has something to move through.
        var i = 0
        while (colors.size < 3) {
            colors += shifted(colors[i % picked.size], if (colors.size == 1) 24f else -24f, if (colors.size == 1) 0.85f else 0.7f)
            i++
        }
        return colors
    }

    private fun near(a: Int, b: Int): Boolean {
        val d = Math.abs(a - b)
        return minOf(d, HUES - d) < 2
    }

    private fun grey(level: Float): Int {
        val v = Math.round(level.coerceIn(0f, 1f) * 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** [r], [g], [b] with its brightness put in the range that works behind white text, its hue and saturation kept. */
    private fun fit(r: Int, g: Int, b: Int): Int {
        val hsv = hsvOf(r, g, b)
        return rgbOf(hsv[0], hsv[1].coerceIn(0.35f, 0.9f), hsv[2].coerceIn(0.4f, 0.78f))
    }

    private fun shifted(color: Int, hueDegrees: Float, value: Float): Int {
        val hsv = hsvOf(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
        return rgbOf((hsv[0] + hueDegrees + 360f) % 360f, hsv[1], (hsv[2] * value).coerceIn(0.3f, 0.78f))
    }

    /** Hue 0..360, saturation and value 0..1. */
    internal fun hsvOf(r: Int, g: Int, b: Int): FloatArray {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = (max - min).toFloat()
        val v = max / 255f
        val s = if (max == 0) 0f else delta / max
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return floatArrayOf(if (h < 0f) h + 360f else h, s, v)
    }

    internal fun rgbOf(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1f - Math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun part(f: Float) = Math.round((f + m) * 255f).coerceIn(0, 255)
        return (0xFF shl 24) or (part(r) shl 16) or (part(g) shl 8) or part(b)
    }
}

package dev.hashsome.headless

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A color as 0..255 channels. */
data class Rgb(val r: Int, val g: Int, val b: Int)

/** Color math for lights, ported from `light-color.ts`: what a white temperature or a hue looks like, for a swatch. */
object LightColors {
    /** `Math.round` of JavaScript: halves go up. */
    private fun round(x: Double): Int = Math.floor(x + 0.5).toInt()

    private fun clamp(value: Double, low: Double, high: Double) = min(high, max(low, value))

    /** Approximate blackbody color for a white temperature (Tanner Helland's curve fit). */
    fun kelvinToRgb(kelvin: Int): Rgb {
        val t = clamp(kelvin.toDouble(), 1000.0, 40000.0) / 100
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
        return Rgb(round(clamp(r, 0.0, 255.0)), round(clamp(g, 0.0, 255.0)), round(clamp(b, 0.0, 255.0)))
    }

    /** [hue] 0..360, [saturation] 0..100, at full brightness. */
    fun hsToRgb(hue: Double, saturation: Double): Rgb {
        val s = clamp(saturation, 0.0, 100.0) / 100
        val h = (((hue % 360) + 360) % 360) / 60
        val c = s
        val x = c * (1 - abs((h % 2) - 1))
        val (r1, g1, b1) = when {
            h < 1 -> Triple(c, x, 0.0)
            h < 2 -> Triple(x, c, 0.0)
            h < 3 -> Triple(0.0, c, x)
            h < 4 -> Triple(0.0, x, c)
            h < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }

        val m = 1 - c
        return Rgb(round((r1 + m) * 255), round((g1 + m) * 255), round((b1 + m) * 255))
    }

    /** Hue (0..360) and saturation (0..100), rounded to whole numbers. */
    fun rgbToHs(rgb: Rgb): Pair<Int, Int> {
        val rn = rgb.r / 255.0
        val gn = rgb.g / 255.0
        val bn = rgb.b / 255.0
        val top = max(rn, max(gn, bn))
        val bottom = min(rn, min(gn, bn))
        val d = top - bottom
        var h = 0.0
        if (d != 0.0) {
            h = when (top) {
                rn -> ((gn - bn) / d) % 6
                gn -> (bn - rn) / d + 2
                else -> (rn - gn) / d + 4
            }

            h *= 60
            if (h < 0) h += 360
        }

        return round(h) to round(if (top == 0.0) 0.0 else d / top * 100)
    }
}

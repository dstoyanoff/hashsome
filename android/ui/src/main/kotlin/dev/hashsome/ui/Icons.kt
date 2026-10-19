package dev.hashsome.ui

import android.graphics.Bitmap
import dev.hashsome.client.WeatherCondition

/**
 * An icon, as the web takes `icon={<Lightbulb />}`: components receive this value, never a name. [res] is a
 * vector drawable's resource id. The consumer's generated pack (`hashsome android icons lucide`) is an
 * object of these (`Lucide.bath`). No `@DrawableRes` here because that annotation lives in AndroidX.
 */
@JvmInline
value class Icon(val res: Int)

/**
 * The ~40 glyphs `ui` draws in its own chrome (close, play/pause, skip, grip, chevrons, search, the weather
 * conditions), generated from lucide (ISC, see NOTICE) into `dev.hashsome.ui.R.drawable`. Components that take
 * an icon from the dashboard do not use these.
 */
object UiIcons {
    val close: Icon get() = TODO("WP3")
    val play: Icon get() = TODO("WP3")
    val pause: Icon get() = TODO("WP3")
    val skipNext: Icon get() = TODO("WP3")
    val skipPrevious: Icon get() = TODO("WP3")
    val shuffle: Icon get() = TODO("WP3")
    val volume: Icon get() = TODO("WP3")
    val volumeMuted: Icon get() = TODO("WP3")
    val grip: Icon get() = TODO("WP3")
    val chevronLeft: Icon get() = TODO("WP3")
    val chevronRight: Icon get() = TODO("WP3")
    val chevronDown: Icon get() = TODO("WP3")
    val chevronUp: Icon get() = TODO("WP3")
    val search: Icon get() = TODO("WP3")
    val list: Icon get() = TODO("WP3")

    /** The glyph for what the sky is doing (with its own tint per shape). */
    fun weather(condition: WeatherCondition, night: Boolean = false): Icon = TODO("WP3")
}

/** [icon] drawn at [px] square in [color], cached by (icon, size, color). */
fun iconBitmap(icon: Icon, px: Int, color: Int): Bitmap = TODO("WP3")

/** Decodes every icon the visible page is going to need, off the first frame. */
fun warmIcons(context: android.content.Context): Unit = TODO("WP3")

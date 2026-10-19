package dev.hashsome.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import dev.hashsome.client.WeatherCondition
import dev.hashsome.ui.icons.Ui

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
    val close: Icon get() = Ui.x
    val play: Icon get() = Ui.play
    val pause: Icon get() = Ui.pause
    val skipNext: Icon get() = Ui.skipForward
    val skipPrevious: Icon get() = Ui.skipBack
    val shuffle: Icon get() = Ui.shuffle
    val volume: Icon get() = Ui.volume2
    val volumeMuted: Icon get() = Ui.volumeX
    val grip: Icon get() = Ui.gripVertical
    val chevronLeft: Icon get() = Ui.chevronLeft
    val chevronRight: Icon get() = Ui.chevronRight
    val chevronDown: Icon get() = Ui.chevronDown
    val chevronUp: Icon get() = Ui.chevronUp
    val search: Icon get() = Ui.search
    val list: Icon get() = Ui.list

    /**
     * The glyph for what the sky is doing (with its own tint per shape: see [WeatherLook]). [night] is accepted for the frozen
     * signature and ignored, as the web's weather icon ignores it: the weather entity reports `clear-night` itself.
     */
    @Suppress("UNUSED_PARAMETER")
    fun weather(condition: WeatherCondition, night: Boolean = false): Icon = WeatherLook.of(condition).icon
}

private val constantStates = HashMap<Int, Drawable.ConstantState>()

/** A fresh drawable of [icon]. The XML is read once (reading a vector drawable is slow on a Shelly display); every later one is a copy of it. */
internal fun iconDrawable(icon: Icon): Drawable {
    // Main thread only: the constant states are a plain map.
    val resources = Hashsome.appContext.resources
    val state = constantStates.getOrPut(icon.res) {
        @Suppress("DEPRECATION")
        resources.getDrawable(icon.res, Hashsome.appContext.theme).constantState!!
    }
    return state.newDrawable(resources).mutate()
}

private data class BitmapKey(val res: Int, val px: Int, val color: Int)

/** The drawn icons, bounded by what they weigh (4 MB): a theme that changes color leaves old ones behind. Main thread only. */
private val bitmaps = object : android.util.LruCache<BitmapKey, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: BitmapKey, value: Bitmap): Int = value.byteCount
}

/** Forgets every drawn icon (the theme's colors changed). */
internal fun clearIconCache() {
    bitmaps.evictAll()
}

/**
 * [icon] drawn at [px] square in [color], cached by (icon, size, color). A vector drawable rasterises itself on its first draw,
 * and a list that made a pair of them per row did that on the main thread as the rows arrived, which stalled the arrival: a
 * bitmap is drawn once and shared. A weather glyph with colors of its own ignores [color] (only its plain parts take it).
 */
fun iconBitmap(icon: Icon, px: Int, color: Int): Bitmap {
    val colored = WeatherLook.isColored(icon.res)
    val key = BitmapKey(icon.res, px, if (colored && WeatherLook.plainOf(icon.res) == null) 0 else color)
    bitmaps.get(key)?.let { return it }
    return run {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun draw(icon: Icon, tint: Int?) {
            val drawable = iconDrawable(icon)
            if (tint != null) drawable.setTint(tint)
            drawable.setBounds(0, 0, px, px)
            drawable.draw(canvas)
        }
        draw(icon, if (colored) null else color)
        WeatherLook.plainOf(icon.res)?.let { draw(it, color) }
        bitmap.also { bitmaps.put(key, it) }
    }
}

/** Decodes every icon the chrome is going to need, off the first frame. */
fun warmIcons(context: android.content.Context) {
    Hashsome.appContext = context.applicationContext
    listOf(
        UiIcons.close, UiIcons.play, UiIcons.pause, UiIcons.skipNext, UiIcons.skipPrevious, UiIcons.shuffle, UiIcons.volume,
        UiIcons.volumeMuted, UiIcons.grip, UiIcons.chevronLeft, UiIcons.chevronRight, UiIcons.chevronDown, UiIcons.chevronUp,
        UiIcons.search, UiIcons.list, Ui.listStart, Ui.listPlus, Ui.trash2, Ui.library, Ui.listMusic, Ui.speaker,
    ).forEach { iconDrawable(it) }
}

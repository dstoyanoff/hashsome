package dev.hashsome.kiosk

// The ramp and the settle-then-fade behaviour follow ShellyElevate's ScreenManager and BrightnessAnimator
// (Apache-2.0, see android/NOTICE): a linear ramp, a delay before a change is acted on, and a one-second fade.
// The hysteresis band is wider than theirs because the X1's light sensor only reports 30 lx steps (spike S2e).

import dev.hashsome.client.Cancel
import dev.hashsome.client.Scheduler
import dev.hashsome.client.Store
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Follows the ambient light to a brightness: [min] at 30 lx or less up to 255 at 500 lx or more, with hysteresis, animated.
 *
 * Hysteresis, in lux: a reading is ignored while it is within [bandFor] of the last one acted on, which is the larger of
 * 45 lx (one and a half steps of the X1's sensor, which flips between 30 and 60 lx in a steady room) and 15 % of that
 * reading (the sensor's relative noise at high light). A reading outside the band must then hold for 3 s before the
 * brightness fades to it over 1 s, so a passing shadow does not move the backlight. The first reading is applied at once.
 * Nothing is written while the screen is off; when it comes on, the brightness it should have is applied at once.
 */
class AutoBrightness(private val lux: Store<Float?>, private val screen: Screen, private val min: Store<Int>) {
    /** Tests give theirs; in the app it is the main looper's. */
    internal var scheduler: Scheduler? = null

    private var running = false
    private val cancels = ArrayList<Cancel>()
    private var settle: Cancel? = null
    private var fade: Cancel? = null

    /** The lux the current target was computed from. */
    private var acted: Float? = null

    /** What was last written to the screen. */
    private var shown: Int? = null

    fun start() {
        if (running) return
        running = true
        val sched = scheduler ?: mainScheduler()
        cancels += lux.listenNow { value -> if (value != null) onLux(sched, value) }
        cancels += min.listen { acted?.let { applyNow(sched, target(it)) } }
        cancels += screen.state.listen { state ->
            if (state == ScreenState.On) acted?.let { applyNow(sched, target(it)) } else cancelTimers()
        }
    }

    fun stop() {
        running = false
        cancels.forEach { it() }
        cancels.clear()
        cancelTimers()
        acted = null
        shown = null
    }

    private fun onLux(sched: Scheduler, value: Float) {
        val last = acted
        if (last == null) {
            acted = value
            applyNow(sched, target(value))
            return
        }
        if (abs(value - last) < bandFor(last)) {
            // Back inside the band: whatever was waiting to be acted on is a flicker.
            settle?.invoke()
            settle = null
            return
        }
        if (screen.state.value == ScreenState.Off) {
            // Nobody sees it; the next wake applies the right level.
            acted = value
            return
        }
        settle?.invoke()
        settle = sched.after(SETTLE_MS) {
            settle = null
            val now = lux.value ?: return@after
            acted = now
            val to = target(now)
            if (to != shown) fadeTo(sched, to)
        }
    }

    private fun applyNow(sched: Scheduler, to: Int) {
        if (screen.state.value == ScreenState.Off) return
        cancelTimers()
        write(to)
    }

    private fun fadeTo(sched: Scheduler, to: Int) {
        if (screen.state.value == ScreenState.Off) return
        fade?.invoke()
        val from = shown ?: return write(to)
        if (abs(to - from) < MIN_ANIMATION_STEP) return write(to)
        val steps = (FADE_MS / FRAME_MS).toInt()
        var step = 0
        fade = sched.every(FRAME_MS) {
            step++
            if (step >= steps) {
                fade?.invoke()
                fade = null
                write(to)
            } else {
                write(from + ((to - from) * step / steps.toFloat()).roundToInt())
            }
        }
    }

    private fun write(level: Int) {
        shown = level
        screen.brightness(level)
    }

    private fun cancelTimers() {
        settle?.invoke()
        settle = null
        fade?.invoke()
        fade = null
    }

    /** The brightness for [lux]: ShellyElevate's ramp, linear from the minimum at 30 lx to 255 at 500 lx. */
    internal fun target(lux: Float): Int = brightnessFor(lux, min.value)

    internal companion object {
        const val SETTLE_MS = 3_000L
        const val FADE_MS = 1_000L
        private const val FRAME_MS = 50L
        private const val MIN_ANIMATION_STEP = 2
        const val LOW_LUX = 30f
        const val HIGH_LUX = 500f

        /** The band within which a new reading is not acted on, around the reading that was. */
        fun bandFor(last: Float): Float = max(45f, last * 0.15f)

        fun brightnessFor(lux: Float, min: Int): Int {
            val floor = min.coerceIn(0, 255)
            if (lux.isNaN() || lux <= LOW_LUX) return floor
            if (lux >= HIGH_LUX) return 255
            return (floor + (255 - floor) * (lux - LOW_LUX) / (HIGH_LUX - LOW_LUX)).roundToInt().coerceIn(0, 255)
        }
    }
}

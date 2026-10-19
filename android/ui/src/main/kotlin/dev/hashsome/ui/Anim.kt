package dev.hashsome.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator

/** CSS's default `ease`. */
val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

/** CSS's `ease-out`. */
internal val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)

/** CSS's `ease-in`. */
internal val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)

/** The curve of a row that arrives: `cubic-bezier(0.2, 0.8, 0.2, 1)`. */
internal val ARRIVE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

/** How long a page takes to come in, in ms (as the web dashboards'). */
const val PAGE_MS = 90L

/**
 * How much the app moves, set by the dashboard shown (`Dashboard.motion`; the web's `motion/mode.ts`). `Full` plays every
 * animation, `Light` the cheap ones that play as something appears (a page, a panel, a row, a press, a color change),
 * `None` nothing: what appears is just there.
 */
internal object Animation {
    var level: Motion = Motion.Full

    /** Whether anything plays as something appears: not in [Motion.None]. */
    val animates: Boolean get() = level != Motion.None

    /** Whether the animations that cost something run (an overlay's exit, a skeleton's pulse, a staggered reveal). */
    val full: Boolean get() = level == Motion.Full
}

/** One float that follows a target by a transition, drawing again at each step: how a property of a view moves without a layout. */
class Eased(private val view: View, start: Float, private val ms: Long = 280) {
    var value = start
        private set
    private var animator: ValueAnimator? = null

    fun to(target: Float, animate: Boolean = true) {
        animator?.cancel()
        if (!animate || ms == 0L || !Animation.animates || !view.isAttachedToWindow || value == target) {
            value = target
            view.invalidate()
            return
        }

        animator = ValueAnimator.ofFloat(value, target).apply {
            duration = ms
            interpolator = EASE
            addUpdateListener {
                value = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }

    /** Stops where it is. */
    fun cancel() {
        animator?.cancel()
    }
}

/** A color that follows a target by a transition. */
class EasedColor(private val view: View, start: Int, private val ms: Long = 280) {
    var value = start
        private set
    private var animator: ValueAnimator? = null

    // Where it is going. Not [value]: a color that has only just started to move is still its old self, and asking for that color
    // back has to stop the move, not be taken for "already there".
    private var goal = start

    fun to(target: Int) {
        if (target == goal) return
        goal = target
        animator?.cancel()
        if (!Animation.animates || !view.isAttachedToWindow) {
            value = target
            view.invalidate()
            return
        }

        val from = value
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = EASE
            addUpdateListener {
                value = mix(from, target, it.animatedValue as Float)
                view.invalidate()
            }
            start()
        }
    }
}

/**
 * Makes a row (or any view) arrive: it fades in and slides 28 dp from the right, [index] rows after the first one starting
 * 55 ms later each (the web's `Reveal light`). In [Motion.None] it is just there.
 */
internal fun View.arrive(index: Int = 0, stepMs: Long = 0) {
    if (!Animation.animates) return
    val resting = alpha
    alpha = 0f
    translationX = dp(28f)
    animate().alpha(resting).translationX(0f).setDuration(300).setStartDelay(index * stepMs).setInterpolator(ARRIVE).start()
}

package dev.hashsome.ui

/** The pure arithmetic of the form controls ([FormSlider], [FormSelect], [FormRow]), unit-tested without a device. */
internal object FormMath {
    /** The height of a form row in dp: the density's round button and a little air, so a touch lands on it as on a tile's button. */
    fun rowHeightDp(density: ResolvedDensity): Int = density.iconCircle + 8

    /** The value a slider shows at [fraction] (0..1) between [min] and [max], whole numbers. */
    fun valueAt(fraction: Float, min: Int, max: Int): Int = (min + Math.round(fraction.coerceIn(0f, 1f) * (max - min))).coerceIn(min, max)

    /** Where [value] sits between [min] and [max], 0..1 (0 when the range is empty). */
    fun fractionOf(value: Int, min: Int, max: Int): Float = if (max <= min) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)

    /** The top of a list of [height] under an anchor spanning [anchorTop]..[anchorBottom]: below it when it fits above [screenHeight], else above it, else pinned to the screen. */
    fun listTop(anchorTop: Int, anchorBottom: Int, height: Int, screenHeight: Int, gap: Int): Int {
        val below = anchorBottom + gap
        if (below + height <= screenHeight) return below
        val above = anchorTop - gap - height
        if (above >= 0) return above
        return (screenHeight - height).coerceAtLeast(0)
    }

    /** The left of a list of [width] whose right edge lines up with the anchor's, kept on a screen of [screenWidth]. */
    fun listLeft(anchorRight: Int, width: Int, screenWidth: Int): Int = (anchorRight - width).coerceIn(0, (screenWidth - width).coerceAtLeast(0))
}

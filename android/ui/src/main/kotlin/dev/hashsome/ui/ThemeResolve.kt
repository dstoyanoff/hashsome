package dev.hashsome.ui


// The pure part of the theme: the built-in values and the merging of overrides into them. Nothing here touches the
// platform's `Typeface`, so all of it runs (and is tested) on a plain JVM.

private fun argb(hex: Long): Int = hex.toInt()

/** The values of packages/ui/src/theme/index.ts: Ember dark and light, the radii, shadows and the type scale. */
internal val DefaultTheme: ThemeConfig = ThemeConfig(
    dark = Palette(
        bg = argb(0xFF0B0A0D),
        surface = argb(0xFF17151A),
        surfaceRaised = argb(0xFF211E26),
        text = argb(0xFFF2EFEA),
        textMuted = argb(0xFF96908C),
        accent = argb(0xFFB85C38),
        accentText = argb(0xFFFFFFFF),
        onAccent = argb(0xFF1B1B1F),
        warm = argb(0xFFE3B341),
        danger = argb(0xFFF2554A),
        success = argb(0xFF34D399),
        line = argb(0xFF726C6A),
        rail = argb(0xFF141216),
        // rgba(255, 255, 255, 0.09)
        border = argb(0x17FFFFFF),
    ),
    light = Palette(
        bg = argb(0xFFF4F1EB),
        surface = argb(0xFFFFFFFF),
        surfaceRaised = argb(0xFFEFEAE1),
        text = argb(0xFF211E26),
        textMuted = argb(0xFF6B6560),
        accent = argb(0xFFFF7A45),
        accentText = argb(0xFFFFFFFF),
        onAccent = argb(0xFF1B1B1F),
        warm = argb(0xFFE3B341),
        danger = argb(0xFFF2554A),
        success = argb(0xFF34D399),
        line = argb(0xFF4A4540),
        rail = argb(0xFFEAE4DA),
        // rgba(0, 0, 0, 0.08)
        border = argb(0x14000000),
    ),
    radius = Radius(full = 999f, card = 25f, drawer = 24f, chrome = 14f, row = 16f, small = 9f),
    shadow = Shadow(
        // 0 20px 48px rgba(0, 0, 0, 0.5)
        drawer = ShadowSpec(blur = 48f, dy = 20f, color = argb(0x80000000)),
        // 0 6px 20px rgba(0, 0, 0, 0.16)
        dock = ShadowSpec(blur = 20f, dy = 6f, color = argb(0x29000000)),
    ),
    typography = Typography(
        mapOf(
            Role.Default to TypographySpec(size = 16),
            Role.Label to TypographySpec(size = 12, weight = 500),
            Role.Secondary to TypographySpec(size = 11),
            Role.Heading to TypographySpec(size = 14, weight = 600),
            Role.Body to TypographySpec(size = 13, weight = 400),
            Role.BodyStrong to TypographySpec(size = 13, weight = 600),
            Role.Eyebrow to TypographySpec(size = 11, weight = 600, letterSpacing = 0.04f),
            Role.Stat to TypographySpec(size = 20, weight = 600),
            Role.Title to TypographySpec(size = 16, weight = 400),
            Role.RoomTitle to TypographySpec(size = 14, weight = 400),
            Role.Clock to TypographySpec(size = 32, weight = 400),
        ),
    ),
)

/** A text style with every field decided: what a view draws with. [lineHeight] is a multiple of the size (null: the font's own). */
data class ResolvedText(val size: Int, val weight: Int, val lineHeight: Float?, val letterSpacing: Float)

/** [role]'s fields over the default style's. */
internal fun Typography.resolved(role: Role): ResolvedText {
    val base = styles[Role.Default] ?: TypographySpec()
    val own = styles[role] ?: TypographySpec()
    return ResolvedText(
        size = own.size ?: base.size ?: 16,
        weight = own.weight ?: base.weight ?: 400,
        lineHeight = own.lineHeight ?: base.lineHeight,
        letterSpacing = own.letterSpacing ?: base.letterSpacing ?: 0f,
    )
}

internal class ResolvedTokens(val palette: Palette, val radius: Radius, val shadow: Shadow, val typography: Typography)

/** Mirrors `applyThemeOverrides`: [base] for [mode] with [overrides] merged in. Pure; neither argument is changed. */
internal fun resolveTokens(base: ThemeConfig, mode: ThemeMode, overrides: ThemeOverrides?): ResolvedTokens {
    val palette = if (mode == ThemeMode.Dark) base.dark else base.light
    if (overrides == null) return ResolvedTokens(palette, base.radius, base.shadow, base.typography)

    val shared = overrides.shared
    val colors = if (mode == ThemeMode.Dark) overrides.dark else overrides.light
    val styles = LinkedHashMap(base.typography.styles)
    for ((role, spec) in shared?.typography.orEmpty()) {
        val own = styles[role] ?: TypographySpec()
        styles[role] = TypographySpec(
            size = spec.size ?: own.size,
            weight = spec.weight ?: own.weight,
            lineHeight = spec.lineHeight ?: own.lineHeight,
            letterSpacing = spec.letterSpacing ?: own.letterSpacing,
        )
    }

    val r = shared?.radius
    val s = shared?.shadow
    return ResolvedTokens(
        palette = palette.over(colors),
        radius = Radius(
            full = r?.full ?: base.radius.full,
            card = r?.card ?: base.radius.card,
            drawer = r?.drawer ?: base.radius.drawer,
            chrome = r?.chrome ?: base.radius.chrome,
            row = r?.row ?: base.radius.row,
            small = r?.small ?: base.radius.small,
        ),
        shadow = Shadow(drawer = s?.drawer ?: base.shadow.drawer, dock = s?.dock ?: base.shadow.dock),
        typography = Typography(styles),
    )
}

private fun Palette.over(o: PaletteOverrides?): Palette = if (o == null) this else Palette(
    bg = o.bg ?: bg,
    surface = o.surface ?: surface,
    surfaceRaised = o.surfaceRaised ?: surfaceRaised,
    text = o.text ?: text,
    textMuted = o.textMuted ?: textMuted,
    accent = o.accent ?: accent,
    accentText = o.accentText ?: accentText,
    onAccent = o.onAccent ?: onAccent,
    warm = o.warm ?: warm,
    danger = o.danger ?: danger,
    success = o.success ?: success,
    line = o.line ?: line,
    rail = o.rail ?: rail,
    border = o.border ?: border,
)

/** The built-in sizes per density (packages/ui/src/theme/density.ts), in dp. */
internal fun builtInDensity(density: Density): ResolvedDensity = when (density) {
    Density.Comfortable -> ResolvedDensity(space = 12, tileHeight = 68, iconSize = 24, iconCircle = 44)
    // Small square displays (e.g. a Shelly wall display).
    Density.Compact -> ResolvedDensity(space = 8, tileHeight = 52, iconSize = 20, iconCircle = 36)
}

/** Mirrors `densityTokens`: the density's tokens with the fields [overrides] names replaced. */
internal fun resolveDensity(density: Density, overrides: ThemeOverrides?): ResolvedDensity {
    val base = builtInDensity(density)
    val o = overrides?.density?.get(density) ?: return base
    return ResolvedDensity(
        space = o.space ?: base.space,
        tileHeight = o.tileHeight ?: base.tileHeight,
        iconSize = o.iconSize ?: base.iconSize,
        iconCircle = o.iconCircle ?: base.iconCircle,
    )
}

// --- Colors ---------------------------------------------------------------------------------------------------------

/** [color] with its alpha scaled by [alpha]. */
fun withAlpha(color: Int, alpha: Float): Int =
    (((color ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

/** [from] to [to] by [t] (0..1), per channel. */
fun mix(from: Int, to: Int, t: Float): Int {
    val f = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return Math.round(a + (b - a) * f)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

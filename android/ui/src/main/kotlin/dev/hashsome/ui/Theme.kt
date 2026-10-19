package dev.hashsome.ui

import android.graphics.Typeface

// The theme model, mirroring packages/ui/src/theme (tokens.ts, density.ts, overrides.ts).
// Colors are ARGB ints, lengths are dp, text sizes are sp. A component reads all of them from the resolved
// `Theme` when it draws and never holds a literal, so an override reaches every component.

enum class ThemeMode { Light, Dark }

/** Which theme a device shows: follow the schedule, or stay on one (the settings sheet and Home Assistant set it). */
enum class ThemeChoice { Auto, Dark, Light }

enum class Density { Comfortable, Compact }

data class Palette(
    val bg: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val text: Int,
    val textMuted: Int,
    val accent: Int,
    val accentText: Int,
    /** Text drawn directly on the accent color. */
    val onAccent: Int,
    val warm: Int,
    val danger: Int,
    /** Something fine or connected (a status dot, a healthy link). */
    val success: Int,
    val line: Int,
    /** The nav rail's background. */
    val rail: Int,
    val border: Int,
)

/** Corner radii in dp. */
data class Radius(val full: Float, val card: Float, val drawer: Float, val chrome: Float, val row: Float, val small: Float)

/** A drop shadow: [blur] and [dy] in dp. */
data class ShadowSpec(val blur: Float, val dy: Float, val color: Int)

data class Shadow(val drawer: ShadowSpec, val dock: ShadowSpec)

/** The named text styles of the type scale (the web's `TypographyName`). */
enum class Role { Default, Label, Secondary, Heading, Body, BodyStrong, Eyebrow, Stat, Title, RoomTitle, Clock }

/** One text style, resolved. [lineHeight] is a multiple of the size, [letterSpacing] is in em. */
data class TypographySpec(
    val size: Int? = null,
    val weight: Int? = null,
    val lineHeight: Float? = null,
    val letterSpacing: Float? = null,
)

data class Typography(val styles: Map<Role, TypographySpec>) {
    operator fun get(role: Role): TypographySpec = styles.getValue(role)
}

/** The built-in theme before overrides: both palettes and the tokens they share. */
data class ThemeConfig(
    val light: Palette,
    val dark: Palette,
    val radius: Radius,
    val shadow: Shadow,
    val typography: Typography,
) {
    companion object {
        /** The values of packages/ui/src/theme/tokens.ts. */
        val Default: ThemeConfig get() = TODO("WP3")
    }
}

// --- Overrides: everything optional, name only what differs ---------------------------------------------------------

/** A palette with only the colors that change. */
data class PaletteOverrides(
    val bg: Int? = null,
    val surface: Int? = null,
    val surfaceRaised: Int? = null,
    val text: Int? = null,
    val textMuted: Int? = null,
    val accent: Int? = null,
    val accentText: Int? = null,
    val onAccent: Int? = null,
    val warm: Int? = null,
    val danger: Int? = null,
    val success: Int? = null,
    val line: Int? = null,
    val rail: Int? = null,
    val border: Int? = null,
)

data class RadiusOverrides(
    val full: Float? = null,
    val card: Float? = null,
    val drawer: Float? = null,
    val chrome: Float? = null,
    val row: Float? = null,
    val small: Float? = null,
)

data class ShadowOverrides(val drawer: ShadowSpec? = null, val dock: ShadowSpec? = null)

/** Tokens both modes share. [typography] is by role; each takes only the fields it changes. */
data class SharedOverrides(
    val radius: RadiusOverrides? = null,
    val shadow: ShadowOverrides? = null,
    val typography: Map<Role, TypographySpec> = emptyMap(),
)

/**
 * Sizes that scale with how much room a display has, in dp. As an argument of [ThemeOverrides.density]
 * it is partial: a null field keeps the density's own value.
 */
data class DensityTokens(
    /** The gap between tiles: three spacing units. Changing it rescales all spacing. */
    val space: Int? = null,
    val tileHeight: Int? = null,
    val iconSize: Int? = null,
    /** The diameter of the round icon buttons and media art. */
    val iconCircle: Int? = null,
)

/** [DensityTokens] with every value filled in. */
data class ResolvedDensity(val space: Int, val tileHeight: Int, val iconSize: Int, val iconCircle: Int) {
    /** One spacing unit: a third of [space]. */
    val unit: Float get() = space / UNITS_PER_SPACE.toFloat()

    companion object {
        const val UNITS_PER_SPACE = 3
    }
}

/**
 * Changes to the built-in theme (mirrors the web's `ThemeOverrides`). The web's `zIndex` override is omitted on
 * purpose: views stack by the order they are added and by elevation, not by numbered layers.
 */
data class ThemeOverrides(
    val light: PaletteOverrides? = null,
    val dark: PaletteOverrides? = null,
    val shared: SharedOverrides? = null,
    val density: Map<Density, DensityTokens> = emptyMap(),
)

/** A theme, resolved for one mode and one density; what views read when they draw. */
class Theme(
    val mode: ThemeMode,
    val palette: Palette,
    val radius: Radius,
    val shadow: Shadow,
    val typography: Typography,
    val density: ResolvedDensity,
    val typefaces: FontSet,
) {
    companion object {
        /** [base] with [overrides] applied for [mode] (pure; neither argument is changed). Mirrors `applyThemeOverrides`. */
        fun resolve(
            base: ThemeConfig,
            mode: ThemeMode,
            overrides: ThemeOverrides?,
            density: Density = Density.Comfortable,
            typefaces: FontSet = FontSet.System,
        ): Theme = TODO("WP3")
    }
}

/** Mirrors `densityTokens`. */
fun Density.tokens(overrides: ThemeOverrides?): ResolvedDensity = TODO("WP3")

// --- Fonts -----------------------------------------------------------------------------------------------------------

/** The three weights the type scale uses, as typefaces. */
class FontSet(val regular: Typeface, val medium: Typeface, val semiBold: Typeface) {
    companion object {
        /** The platform's own sans-serif, for tests and as the fallback while a bundled font loads. */
        val System: FontSet
            get() = FontSet(Typeface.SANS_SERIF, Typeface.SANS_SERIF, Typeface.SANS_SERIF)
    }
}

/** Where a [FontSet] comes from. */
fun interface FontSource {
    fun load(context: android.content.Context): FontSet
}

object Fonts {
    /** The Inter Regular, Medium and SemiBold bundled in `ui`. Another font is a `FontSource` of the consumer's own. */
    val Inter: FontSource = FontSource { TODO("WP3") }
}

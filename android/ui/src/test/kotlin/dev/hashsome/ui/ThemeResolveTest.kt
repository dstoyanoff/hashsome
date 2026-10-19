package dev.hashsome.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeResolveTest {
    private val base = ThemeConfig.Default

    @Test
    fun `the built-in dark and light palettes are the web's Ember`() {
        assertEquals(0xFF0B0A0D.toInt(), base.dark.bg)
        assertEquals(0xFF17151A.toInt(), base.dark.surface)
        assertEquals(0xFFB85C38.toInt(), base.dark.accent)
        assertEquals(0x17FFFFFF, base.dark.border)
        assertEquals(0xFFF4F1EB.toInt(), base.light.bg)
        assertEquals(0xFFFF7A45.toInt(), base.light.accent)
        assertEquals(0xFFEAE4DA.toInt(), base.light.rail)
        assertEquals(0x14000000, base.light.border)
        assertEquals(0xFF1B1B1F.toInt(), base.light.onAccent)
    }

    @Test
    fun `the shared tokens are the web's`() {
        assertEquals(Radius(full = 999f, card = 25f, drawer = 24f, chrome = 14f, row = 16f, small = 9f), base.radius)
        assertEquals(ShadowSpec(blur = 48f, dy = 20f, color = 0x80000000.toInt()), base.shadow.drawer)
        assertEquals(ShadowSpec(blur = 20f, dy = 6f, color = 0x29000000), base.shadow.dock)
        val clock = base.typography.resolved(Role.Clock)
        assertEquals(32, clock.size)
        assertEquals(400, clock.weight)
        val eyebrow = base.typography.resolved(Role.Eyebrow)
        assertEquals(ResolvedText(size = 11, weight = 600, lineHeight = null, letterSpacing = 0.04f), eyebrow)
    }

    @Test
    fun `no overrides gives the base for the mode`() {
        val dark = resolveTokens(base, ThemeMode.Dark, null)
        assertSame(base.dark, dark.palette)
        assertSame(base.radius, dark.radius)
        assertSame(base.light, resolveTokens(base, ThemeMode.Light, null).palette)
    }

    @Test
    fun `a palette override changes its own mode only and only the named colors`() {
        val overrides = ThemeOverrides(dark = PaletteOverrides(accent = 0xFF112233.toInt()))
        val dark = resolveTokens(base, ThemeMode.Dark, overrides).palette
        val light = resolveTokens(base, ThemeMode.Light, overrides).palette
        assertEquals(0xFF112233.toInt(), dark.accent)
        assertEquals(base.dark.bg, dark.bg)
        assertEquals(base.dark.border, dark.border)
        assertEquals(base.light, light)
    }

    @Test
    fun `shared radius and shadow overrides are partial`() {
        val spec = ShadowSpec(blur = 10f, dy = 2f, color = 0x11000000)
        val overrides = ThemeOverrides(shared = SharedOverrides(radius = RadiusOverrides(card = 30f), shadow = ShadowOverrides(dock = spec)))
        val tokens = resolveTokens(base, ThemeMode.Dark, overrides)
        assertEquals(30f, tokens.radius.card)
        assertEquals(base.radius.drawer, tokens.radius.drawer)
        assertEquals(base.radius.full, tokens.radius.full)
        assertEquals(spec, tokens.shadow.dock)
        assertEquals(base.shadow.drawer, tokens.shadow.drawer)
        // Both modes share them.
        assertEquals(30f, resolveTokens(base, ThemeMode.Light, overrides).radius.card)
    }

    @Test
    fun `typography is overridden by role and a role keeps the fields it is not given`() {
        val overrides = ThemeOverrides(
            shared = SharedOverrides(
                typography = mapOf(Role.Body to TypographySpec(size = 14), Role.Clock to TypographySpec(weight = 600, letterSpacing = 0.1f)),
            ),
        )
        val t = resolveTokens(base, ThemeMode.Dark, overrides).typography
        assertEquals(ResolvedText(size = 14, weight = 400, lineHeight = null, letterSpacing = 0f), t.resolved(Role.Body))
        assertEquals(ResolvedText(size = 32, weight = 600, lineHeight = null, letterSpacing = 0.1f), t.resolved(Role.Clock))
        // The others are as they were.
        assertEquals(base.typography.resolved(Role.Heading), t.resolved(Role.Heading))
        // The base itself is not changed.
        assertEquals(13, base.typography.resolved(Role.Body).size)
    }

    @Test
    fun `a role without a field of its own takes the default style's`() {
        val overrides = ThemeOverrides(shared = SharedOverrides(typography = mapOf(Role.Default to TypographySpec(weight = 500, lineHeight = 1.4f))))
        val t = resolveTokens(base, ThemeMode.Dark, overrides).typography
        // Secondary has a size of its own and no weight: the default's.
        assertEquals(ResolvedText(size = 11, weight = 500, lineHeight = 1.4f, letterSpacing = 0f), t.resolved(Role.Secondary))
        // Heading has its own weight.
        assertEquals(600, t.resolved(Role.Heading).weight)
    }

    @Test
    fun `density tokens are the built-in ones, with the named fields replaced`() {
        assertEquals(ResolvedDensity(space = 12, tileHeight = 68, iconSize = 24, iconCircle = 44), resolveDensity(Density.Comfortable, null))
        assertEquals(ResolvedDensity(space = 8, tileHeight = 52, iconSize = 20, iconCircle = 36), resolveDensity(Density.Compact, null))
        val overrides = ThemeOverrides(density = mapOf(Density.Comfortable to DensityTokens(space = 16)))
        assertEquals(ResolvedDensity(space = 16, tileHeight = 68, iconSize = 24, iconCircle = 44), resolveDensity(Density.Comfortable, overrides))
        // Another density is not touched.
        assertEquals(resolveDensity(Density.Compact, null), resolveDensity(Density.Compact, overrides))
        assertEquals(resolveDensity(Density.Comfortable, overrides), Density.Comfortable.tokens(overrides))
    }

    @Test
    fun `a spacing unit is a third of a space`() {
        assertEquals(4f, resolveDensity(Density.Comfortable, null).unit, 0.0001f)
        assertEquals(8f / 3f, resolveDensity(Density.Compact, null).unit, 0.0001f)
    }

    @Test
    fun `mix goes per channel from one color to the other`() {
        assertEquals(0xFF000000.toInt(), mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f))
        assertEquals(0xFFFFFFFF.toInt(), mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f))
        // The accent mixed 70/30 with black, the badge of a lit tile: the web's color-mix(accent 70%, black).
        assertEquals(0xFF814027.toInt(), mix(0xFFB85C38.toInt(), 0xFF000000.toInt(), 0.3f))
    }

    @Test
    fun `withAlpha scales the alpha it has`() {
        assertEquals(0x80FF0000.toInt(), withAlpha(0xFFFF0000.toInt(), 128 / 255f))
        assertEquals(0x00FFFFFF, withAlpha(0x17FFFFFF, 0f))
    }
}

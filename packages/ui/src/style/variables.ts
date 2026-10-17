import type { ThemeConfig } from 'e-prim';
import type { DensityTokens } from '../theme/density.ts';
import { UNITS_PER_SPACE } from '../theme/density.ts';
import { BADGE, HEADER, NAV_BAR, NAV_RAIL, TOP_ROW } from '../theme/grid.ts';
import {
  PALETTE_KEYS,
  RADIUS_KEYS,
  SHADOW_KEYS,
  TYPOGRAPHY_KEYS,
  Z_INDEX_KEYS,
  kebab,
} from './names.ts';

const px = (value: unknown): string | undefined =>
  typeof value === 'number' ? `${value}px` : typeof value === 'string' ? value : undefined;

/**
 * The custom properties a theme and a density come to, as the body of a `:root` rule: `--hs-surface`, `--hs-radius-card`,
 * `--hs-t-label-size`, `--hs-unit`, ... The utility classes and a component's own styles are written in terms of these,
 * so a different theme (the sun going down), density or set of overrides is this text being replaced, and nothing is
 * drawn again.
 */
export function themeVariables(theme: ThemeConfig, density: DensityTokens): string {
  const lines: string[] = [];
  const put = (name: string, value: string | number | undefined) => {
    if (value !== undefined) {
      lines.push(`--hs-${name}: ${value};`);
    }
  };

  const palette = theme.palette as Record<string, string>;
  for (const key of PALETTE_KEYS) {
    put(kebab(key), palette[key]);
  }

  const radius = theme.radius as unknown as Record<string, string>;
  for (const key of RADIUS_KEYS) {
    put(`radius-${kebab(key)}`, radius[key]);
  }

  const shadow = theme.shadow as unknown as Record<string, string>;
  for (const key of SHADOW_KEYS) {
    put(`shadow-${kebab(key)}`, shadow[key]);
  }

  const zIndex = theme.zIndex as unknown as Record<string, number>;
  for (const key of Z_INDEX_KEYS) {
    put(`z-${kebab(key)}`, zIndex[key]);
  }

  put('border-width', px(theme.border?.width ?? 1));

  // A variant is what `default` says, with its own fields over it; a field neither has is the parent's.
  const typography = theme.typography as unknown as Record<string, Record<string, unknown>>;
  const base = typography.default ?? {};
  put('font-family', px(base.fontFamily));
  for (const key of TYPOGRAPHY_KEYS) {
    const specs = { ...base, ...(key === 'default' ? {} : typography[key]) };
    const name = kebab(key);
    put(`t-${name}-size`, px(specs.fontSize) ?? 'inherit');
    put(`t-${name}-weight`, specs.fontWeight === undefined ? 'inherit' : String(specs.fontWeight));
    put(`t-${name}-line`, specs.lineHeight === undefined ? 'inherit' : String(specs.lineHeight));
    put(`t-${name}-spacing`, px(specs.letterSpacing) ?? 'inherit');
  }

  // Density: the spacing unit follows it (a third of a space), and the rest are sizes that scale with the room a display has.
  put('space', `${density.space}px`);
  put('unit', `${density.space / UNITS_PER_SPACE}px`);
  put('tile-height', `${density.tileHeight}px`);
  put('icon-size', `${density.iconSize}px`);
  put('icon-circle', `${density.iconCircle}px`);

  // Sizes that are the same in every density, which the grid maths in JavaScript and the stylesheets both go by.
  put('badge', `${BADGE}px`);
  put('header', `${HEADER}px`);
  put('top-row', `${TOP_ROW}px`);
  put('nav-rail', `${NAV_RAIL}px`);
  put('nav-bar', `${NAV_BAR}px`);

  return lines.join('\n');
}

/** The `<style>` text that installs `themeVariables` for the whole page. */
export const themeCss = (theme: ThemeConfig, density: DensityTokens): string =>
  // Native controls (a date picker's calendar glyph, scrollbars) follow the theme, not the OS.
  `:root {\ncolor-scheme: ${isDark((theme.palette as Record<string, string>).bg ?? '') ? 'dark' : 'light'};\n${themeVariables(theme, density)}\n}`;

function isDark(hex: string): boolean {
  const n = parseInt(hex.replace('#', '').slice(0, 6), 16);
  return Number.isFinite(n) && ((n >> 16) & 255) + ((n >> 8) & 255) + (n & 255) < 384;
}

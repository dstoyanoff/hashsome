import { createContext, useContext } from 'react';
import type { ThemeConfig } from 'e-prim';
import type { PaletteName, TypographyName } from '../style/names.ts';
import { darkTheme } from './index.ts';

/** The theme in effect, resolved (light or dark, the font, the overrides), for the few things that cannot draw from the stylesheet's
 * custom properties: a chart's SVG, or color arithmetic. Everything else says `var(--hs-accent)` and the like in its CSS. */
export const ThemeContext = createContext<ThemeConfig>(darkTheme);

export const useThemeConfig = (): ThemeConfig => useContext(ThemeContext);

/** A palette color's value, e.g. `#B85C38`. */
export function useColor(key: PaletteName): string {
  const palette = useThemeConfig().palette as unknown as Record<string, string>;
  return palette[key] ?? '';
}

/** A typography variant's resolved font size in px, for the rare consumer that can't use a class — e.g. an SVG chart's axis ticks. */
export function useTypographySize(key: Exclude<TypographyName, 'default'>): number {
  const { typography } = useThemeConfig();
  const specs = typography as unknown as Record<string, { fontSize?: unknown } | undefined>;
  const size = specs[key]?.fontSize ?? typography.default.fontSize;
  return typeof size === 'number' ? size : 16;
}

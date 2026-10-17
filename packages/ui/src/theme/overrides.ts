import type { TypographyName } from '../style/names.ts';
import type { ThemeConfig, TypographySpecs } from './tokens.ts';
import { DENSITY, type Density, type DensityTokens } from './density.ts';

/** Changes to the built-in theme. Everything is optional and partial: name only what differs and
 * the rest keeps its default. Only existing tokens can be changed. */
export interface ThemeOverrides {
  /** Colors for the light theme (`theme="light"`, or `"system"` while the OS is light). */
  light?: { palette?: Partial<ThemeConfig['palette']> };

  /** Colors for the dark theme. Light and dark are separate, so one change never touches both. */
  dark?: { palette?: Partial<ThemeConfig['palette']> };

  /** Tokens both themes share. */
  shared?: {
    radius?: Partial<ThemeConfig['radius']>;
    shadow?: Partial<ThemeConfig['shadow']>;
    zIndex?: Partial<ThemeConfig['zIndex']>;

    /** By variant name (`label`, `body`, `stat`, ...); each takes only the fields it changes. */
    typography?: Partial<Record<TypographyName, TypographySpecs>>;
  };

  /** Sizes per density, e.g. `{ comfortable: { space: 16 } }`. */
  density?: Partial<Record<Density, Partial<DensityTokens>>>;
}

/** `base` with `overrides` applied. Pure; neither argument is changed. */
export function applyThemeOverrides(
  base: ThemeConfig,
  mode: 'light' | 'dark',
  overrides: ThemeOverrides | undefined,
): ThemeConfig {
  if (!overrides) {
    return base;
  }

  const shared = overrides.shared ?? {};
  const typography = { ...base.typography } as Record<string, TypographySpecs>;
  for (const [variant, specs] of Object.entries(shared.typography ?? {})) {
    typography[variant] = { ...typography[variant], ...specs };
  }

  return {
    ...base,
    radius: { ...base.radius, ...shared.radius },
    shadow: { ...base.shadow, ...shared.shadow },
    zIndex: { ...base.zIndex, ...shared.zIndex },
    typography: typography as unknown as ThemeConfig['typography'],
    palette: { ...base.palette, ...overrides[mode]?.palette },
  };
}

/** The density's tokens with `overrides` applied. */
export function densityTokens(
  density: Density,
  overrides: ThemeOverrides | undefined,
): DensityTokens {
  return { ...DENSITY[density], ...overrides?.density?.[density] };
}

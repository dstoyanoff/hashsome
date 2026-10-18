import type { ThemeConfig } from './tokens.ts';

export type {
  Palette,
  Radius,
  Shadow,
  ThemeConfig,
  Typography,
  TypographySpecs,
  ZIndex,
} from './tokens.ts';

/** The Google Fonts family loaded and applied by default — see `HashsomeProviderProps.font` . Any other Google Fonts family name works the same way. */
export const DEFAULT_FONT = 'Inter';

const FALLBACK_FONTS = "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif";

/** Builds a CSS `font-family` value for a chosen font, falling back to the system stack if it
 * hasn't loaded yet (or fails to). */
export function fontStack(family: string): string {
  return `'${family}', ${FALLBACK_FONTS}`;
}

/** The Google Fonts CSS endpoint for a family name, covering the weights `@hashsome/ui` actually uses
 * (400/500/600/700). Spaces become `+` per Google Fonts' own URL convention. */
export function googleFontHref(family: string): string {
  return `https://fonts.googleapis.com/css2?family=${family.trim().replace(/\s+/g, '+')}:wght@400;500;600;700&display=swap`;
}

/** Returns `theme` with every `Typography` variant's font swapped to `family` (falling back to
 * the system stack) — every variant inherits `typography.default`, so this is the one place that
 * needs to change. Used by `HashsomeProvider` to apply `HashsomeProviderProps.font` at runtime, since the
 * `darkTheme`/`lightTheme` exports below are built once with the compile-time default. */
export function withFontFamily(theme: ThemeConfig, family: string): ThemeConfig {
  return {
    ...theme,
    typography: {
      ...theme.typography,
      default: { ...theme.typography.default, fontFamily: fontStack(family) },
    },
  };
}

const FONT_FAMILY = fontStack(DEFAULT_FONT);

const shared = {
  zIndex: {
    nav: 5,
    dropdown: 10,
    scrim: 20,
    drawer: 21,
    popover: 30,
    modal: 40,
  },
  shadow: {
    drawer: '0 20px 48px rgba(0, 0, 0, 0.5)',
    dock: '0 6px 20px rgba(0, 0, 0, 0.16)',
  },
  radius: {
    full: '999px',
    card: '25px',
    drawer: '24px',
    chrome: '14px',
    row: '16px',
    small: '9px',
  },
  border: { color: 'border', width: 1 },
  typography: {
    default: { fontFamily: FONT_FAMILY, fontSize: 16 },
    label: { fontSize: 12, fontWeight: 500 },
    secondary: { fontSize: 11 },
    heading: { fontSize: 14, fontWeight: 600 },
    body: { fontSize: 13, fontWeight: 400 },
    bodyStrong: { fontSize: 13, fontWeight: 600 },
    eyebrow: { fontSize: 11, fontWeight: 600, letterSpacing: '0.04em' },
    stat: { fontSize: 20, fontWeight: 600 },
    title: { fontSize: 16, fontWeight: 400 },
    roomTitle: { fontSize: 14, fontWeight: 400 },
    clock: { fontSize: 32, fontWeight: 400 },
  },
} satisfies Omit<ThemeConfig, 'palette'>;

/** The converged "Ember" dark palette (default; the only theme currently wired up). */
export const darkTheme: ThemeConfig = {
  ...shared,
  palette: {
    bg: '#0B0A0D',
    surface: '#17151A',
    surfaceRaised: '#211E26',
    text: '#F2EFEA',
    textMuted: '#96908C',
    accent: '#B85C38',
    accentText: '#FFFFFF',
    onAccent: '#1B1B1F',
    warm: '#E3B341',
    danger: '#F2554A',
    success: '#34D399',
    line: '#726C6A',
    rail: '#141216',
    border: 'rgba(255, 255, 255, 0.09)',
  },
};

/** Ember's light counterpart, selected via `<HashsomeProvider theme="light">` (or `"system"`). */
export const lightTheme: ThemeConfig = {
  ...shared,
  palette: {
    bg: '#F4F1EB',
    surface: '#FFFFFF',
    surfaceRaised: '#EFEAE1',
    text: '#211E26',
    textMuted: '#6B6560',
    accent: '#FF7A45',
    accentText: '#FFFFFF',
    onAccent: '#1B1B1F',
    warm: '#E3B341',
    danger: '#F2554A',
    success: '#34D399',
    line: '#4A4540',
    rail: '#EAE4DA',
    border: 'rgba(0, 0, 0, 0.08)',
  },
};

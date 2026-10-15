import type { Interpolation, Theme } from '@emotion/react';

/** The scrollbar's width (its track) and how much of that is the visible thumb. */
const SCROLLBAR = 14;
const THUMB = 4;

function isDark(hex: string): boolean {
  const n = parseInt(hex.replace('#', '').slice(0, 6), 16);
  return Number.isFinite(n) && ((n >> 16) & 255) + ((n >> 8) & 255) + (n & 255) < 384;
}

/** The app-wide styles `HashsomeProvider` installs once: the page background/text/font, native
 * controls following the theme, border-box sizing, and a low-specificity `button`/`input` reset (so a component's
 * own class always wins over it). Global because the theme is an app property, not a per-page one. */
export function globalStyles({ palette, typography }: Theme): Interpolation<Theme> {
  return {
    ':root': {
      // Native controls (date pickers' calendar glyph, scrollbars) follow the theme, not the OS.
      colorScheme: isDark(palette.bg) ? 'dark' : 'light',
    },
    '*, *::before, *::after': { boxSizing: 'border-box' },
    // The page scrolls inside itself, never the document, so an overscroll has nothing to show: it is not left to
    // pull the page about, or to browsers that take a sideways one for "back".
    'html, body': { overscrollBehavior: 'none' },
    body: {
      background: palette.bg,
      color: palette.text,
      fontFamily: typography.default.fontFamily,
    },
    // `?motion=reduced` (see `HashsomeProvider`): no CSS transitions or animations, except the ones
    // that are a cue the user waits for (a hold's progress line, marked `data-keep-motion`).
    'html[data-motion="reduced"] *:not([data-keep-motion]), html[data-motion="reduced"] *:not([data-keep-motion])::before, html[data-motion="reduced"] *:not([data-keep-motion])::after':
      { transition: 'none !important', animation: 'none !important' },
    button: { font: 'inherit', color: 'inherit' },
    // Buttons and inputs start borderless; one that wants a border asks for it with the `border` prop.
    'button, input': { border: 0 },
    // Scrollbars: thin, no track, in the theme's colors. The track is wider than the thumb and the
    // thumb has a transparent border around it, so there is air between the bar and the content.
    // (Chrome and Safari take the `::-webkit-scrollbar` rules; Firefox, which has no equivalent for
    // the gap, gets a thin bar from the standard properties. Setting those in Chrome would turn the
    // webkit rules off, hence the `@supports`.)
    '::-webkit-scrollbar': { width: SCROLLBAR, height: SCROLLBAR },
    '::-webkit-scrollbar-track, ::-webkit-scrollbar-corner': { background: 'transparent' },
    '::-webkit-scrollbar-thumb': {
      background: palette.border,
      backgroundClip: 'padding-box',
      border: `${(SCROLLBAR - THUMB) / 2}px solid transparent`,
      borderRadius: 999,
    },
    '::-webkit-scrollbar-thumb:hover': { background: palette.line, backgroundClip: 'padding-box' },
    '@supports not selector(::-webkit-scrollbar)': {
      '*': { scrollbarWidth: 'thin', scrollbarColor: `${palette.border} transparent` },
    },
  };
}

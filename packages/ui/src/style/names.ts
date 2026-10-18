// The names of the design tokens, which the stylesheets are made from. The values are in `../theme`
// (the palettes, radii, shadows, layers and type scale), and reach the page as CSS custom properties (`variables.ts`), so a
// theme or density change is a few properties on the root, not every component drawn again. A test keeps these in step with
// the theme.

/** The colors every theme has. */
export const PALETTE_KEYS = [
  'bg',
  'surface',
  'surfaceRaised',
  'text',
  'textMuted',
  'accent',
  'accentText',
  'onAccent',
  'warm',
  'danger',
  'success',
  'line',
  'rail',
  'border',
] as const;

export const RADIUS_KEYS = ['full', 'card', 'drawer', 'chrome', 'row', 'small'] as const;
export const SHADOW_KEYS = ['drawer', 'dock'] as const;
export const Z_INDEX_KEYS = ['nav', 'dropdown', 'scrim', 'drawer', 'popover', 'modal'] as const;

/** The type scale, `default` being what every other variant starts from. Keep `type.module.css` in sync: it has one class per name here. */
export const TYPOGRAPHY_KEYS = [
  'default',
  'label',
  'secondary',
  'heading',
  'body',
  'bodyStrong',
  'eyebrow',
  'stat',
  'title',
  'roomTitle',
  'clock',
] as const;

export type PaletteName = (typeof PALETTE_KEYS)[number];
export type RadiusName = (typeof RADIUS_KEYS)[number];
export type ShadowName = (typeof SHADOW_KEYS)[number];
export type ZIndexName = (typeof Z_INDEX_KEYS)[number];
export type TypographyName = (typeof TYPOGRAPHY_KEYS)[number];

/** `surfaceRaised` as `surface-raised`: how a token is named in a class or a custom property. */
export const kebab = (name: string): string =>
  name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);

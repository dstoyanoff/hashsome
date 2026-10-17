// The names of the design tokens, which the stylesheets and the primitives are both made from. The values are in `../theme`
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

/** The type scale, `default` being what every other variant starts from. */
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

/** What a spacing prop (`p`, `gap`, ...) can say as a number of the theme's spacing unit and have a class ready for. Any
 * other number works too, as an inline style. */
export const SPACING_UNITS = [
  0, 0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2, 2.25, 2.5, 2.75, 3, 3.25, 3.5, 3.75, 4, 4.5, 5, 5.5, 6,
  7, 8, 9, 10, 12, 16, 20, 24,
] as const;

/** The negative ones, which only margins take. */
export const NEGATIVE_SPACING_UNITS = [-0.5, -1, -1.5, -2, -2.5, -3, -4] as const;

/** The spacing props and the CSS they set. */
export const SPACING_PROPS = {
  m: ['margin'],
  mx: ['margin-left', 'margin-right'],
  my: ['margin-top', 'margin-bottom'],
  mt: ['margin-top'],
  mr: ['margin-right'],
  mb: ['margin-bottom'],
  ml: ['margin-left'],
  p: ['padding'],
  px: ['padding-left', 'padding-right'],
  py: ['padding-top', 'padding-bottom'],
  pt: ['padding-top'],
  pr: ['padding-right'],
  pb: ['padding-bottom'],
  pl: ['padding-left'],
  gap: ['gap'],
} as const;

export type SpacingProp = keyof typeof SPACING_PROPS;

/** `surfaceRaised` as `surface-raised`: how a token is named in a class or a custom property. */
export const kebab = (name: string): string =>
  name.replace(/[A-Z]/g, (letter) => `-${letter.toLowerCase()}`);

/** The class for a number of spacing units: `1.5` as `1_5`, `-2` as `n2`. */
export const unitSuffix = (units: number): string =>
  `${units < 0 ? 'n' : ''}${String(Math.abs(units)).replace('.', '_')}`;

/// <reference types="vite/client" />
import type { ComponentPropsWithRef, CSSProperties, ElementType, ReactElement } from 'react';
import {
  NEGATIVE_SPACING_UNITS,
  PALETTE_KEYS,
  RADIUS_KEYS,
  SHADOW_KEYS,
  SPACING_PROPS,
  SPACING_UNITS,
  TYPOGRAPHY_KEYS,
  Z_INDEX_KEYS,
  kebab,
  unitSuffix,
  type PaletteName,
  type RadiusName,
  type ShadowName,
  type SpacingProp,
  type TypographyName,
  type ZIndexName,
} from './names.ts';
import './utilities.css';

// The primitives every component is drawn from: a `Box`, a `Flex`, a `Grid` and a `Typography` that take the design
// system's tokens as props (`<Flex gap={2} radius="card" background="surface">`) and turn them into classes that are
// already in the stylesheet (`utilities.css`), joined as strings: nothing is worked out per element, and no style is
// registered while a page is drawn. A value the stylesheet has no class for (a width in px, a number of spacing units it
// has no step for, a color that is not a token) is an inline style instead.

/** A number of the theme's spacing unit, or CSS as it is (`'10%'`, `'auto'`). */
export type SpacingUnit = string | number;
type Spacing = SpacingUnit | readonly [SpacingUnit, SpacingUnit, ...SpacingUnit[]];

export type PaletteKey = PaletteName | 'transparent';
export type TypographyKey = TypographyName;

export interface SystemProps {
  m?: Spacing;
  mx?: SpacingUnit;
  my?: SpacingUnit;
  mt?: SpacingUnit;
  mr?: SpacingUnit;
  mb?: SpacingUnit;
  ml?: SpacingUnit;
  p?: Spacing;
  px?: SpacingUnit;
  py?: SpacingUnit;
  pt?: SpacingUnit;
  pr?: SpacingUnit;
  pb?: SpacingUnit;
  pl?: SpacingUnit;
  position?: CSSProperties['position'];
  overflow?: CSSProperties['overflow'];
  minWidth?: CSSProperties['minWidth'];
  width?: CSSProperties['width'];
  maxWidth?: CSSProperties['maxWidth'];
  minHeight?: CSSProperties['minHeight'];
  height?: CSSProperties['height'];
  maxHeight?: CSSProperties['maxHeight'];
  grow?: number;

  /** A color of the theme, or any CSS color. */
  background?: PaletteKey | (string & {});
  color?: PaletteKey | (string & {});
  shadow?: ShadowName;

  /** `true` is the theme's own border color. */
  border?: PaletteKey | boolean;
  borderTop?: PaletteKey | boolean;
  borderRight?: PaletteKey | boolean;
  borderBottom?: PaletteKey | boolean;
  borderLeft?: PaletteKey | boolean;
  radius?: RadiusName;
  cursor?: CSSProperties['cursor'];
  zIndex?: ZIndexName;
  typography?: TypographyKey;
}

export interface FlexSystem {
  direction?: CSSProperties['flexDirection'];
  center?: boolean;
  centerMain?: boolean;
  centerCross?: boolean;
  gap?: SpacingUnit;
  justify?: CSSProperties['justifyContent'];
  align?: CSSProperties['alignItems'];

  /** `inline-flex` instead of `flex`. */
  inline?: boolean;
}

export interface GridSystem {
  gap?: SpacingUnit;

  /** `[7]` is seven equal columns, `[7, '1fr']` the same, `[n, 'minmax(0, 1fr)']` n of that size, and a list of sizes is the columns themselves. */
  columns?: readonly [number | string, (string | number | readonly (string | number)[])?];
}

export interface TypographySystem {
  variant?: TypographyKey;
  align?: CSSProperties['textAlign'];
  noWrap?: boolean;
  uppercase?: boolean;
  textOverflow?: CSSProperties['textOverflow'];
}

type Own<E extends ElementType, S> = S & { as?: E };
export type BoxProps<E extends ElementType = 'div'> = Own<E, SystemProps> &
  Omit<ComponentPropsWithRef<E>, keyof SystemProps | 'as'>;

export type FlexProps<E extends ElementType = 'div'> = Own<E, SystemProps & FlexSystem> &
  Omit<ComponentPropsWithRef<E>, keyof SystemProps | keyof FlexSystem | 'as'>;

export type GridProps<E extends ElementType = 'div'> = Own<E, SystemProps & GridSystem> &
  Omit<ComponentPropsWithRef<E>, keyof SystemProps | keyof GridSystem | 'as'>;

export type TypographyProps<E extends ElementType = 'span'> = Own<
  E,
  SystemProps & TypographySystem
> &
  Omit<ComponentPropsWithRef<E>, keyof SystemProps | keyof TypographySystem | 'as'>;

const set = <T extends string | number>(values: readonly T[]) => new Set<unknown>(values);
const SPACING = set(SPACING_UNITS);
const NEGATIVE = set(NEGATIVE_SPACING_UNITS);
const PALETTE = set(PALETTE_KEYS);
const RADII = set(RADIUS_KEYS);
const SHADOWS = set(SHADOW_KEYS);
const LAYERS = set(Z_INDEX_KEYS);
const TYPE = set(TYPOGRAPHY_KEYS);
const POSITIONS = set(['relative', 'absolute', 'fixed', 'sticky', 'static']);
const OVERFLOWS = set(['hidden', 'auto', 'visible', 'scroll', 'clip']);
const CURSORS = set(['pointer', 'default', 'grab', 'progress', 'inherit', 'not-allowed']);
const DIRECTIONS = set(['row', 'column', 'row-reverse', 'column-reverse']);
const ALIGNS = set(['center', 'flex-start', 'flex-end', 'baseline', 'stretch']);
const JUSTIFIES = set([
  'center',
  'flex-start',
  'flex-end',
  'space-between',
  'space-around',
  'space-evenly',
]);

const TEXT_ALIGNS = set(['left', 'center', 'right', 'justify']);

/** The names a primitive takes as props rather than passes on to the element it draws. */
const SYSTEM = new Set<string>([
  ...Object.keys(SPACING_PROPS),
  'position',
  'overflow',
  'minWidth',
  'width',
  'maxWidth',
  'minHeight',
  'height',
  'maxHeight',
  'grow',
  'background',
  'color',
  'shadow',
  'border',
  'borderTop',
  'borderRight',
  'borderBottom',
  'borderLeft',
  'radius',
  'cursor',
  'zIndex',
  'typography',
  'direction',
  'center',
  'centerMain',
  'centerCross',
  'gap',
  'justify',
  'align',
  'inline',
  'columns',
  'variant',
  'noWrap',
  'uppercase',
  'textOverflow',
  'as',
  'className',
  'style',
]);

/** One spacing value as CSS: a number is that many spacing units. */
const spaceCss = (value: SpacingUnit): string =>
  typeof value === 'number' ? `calc(var(--hs-unit) * ${value})` : value;

const sizeCss = (value: unknown): string | number | undefined =>
  typeof value === 'number' ? `${value}px` : (value as string | undefined);

/** What a primitive's props come to: the classes, the inline styles for what has no class, and the props left for the element. */
function resolve(props: Record<string, unknown>, extra: string[]) {
  let className = '';
  let style: Record<string, string | number | undefined> | undefined;
  const inline = (name: string, value: string | number | undefined) => {
    (style ??= {})[name] = value;
  };

  // Spacing: a step the stylesheet has a class for, else inline.
  for (const prop of Object.keys(SPACING_PROPS) as SpacingProp[]) {
    const value = props[prop];
    if (value === undefined) {
      continue;
    }

    if (
      typeof value === 'number' &&
      (SPACING.has(value) || (prop[0] === 'm' && NEGATIVE.has(value)))
    ) {
      className += ` hs-${prop}-${unitSuffix(value)}`;
    } else if (value === 'auto' && (prop === 'm' || prop === 'mt' || prop === 'ml')) {
      className += ` hs-${prop}-auto`;
    } else {
      const css = Array.isArray(value)
        ? (value as SpacingUnit[]).map(spaceCss).join(' ')
        : spaceCss(value as SpacingUnit);

      for (const property of SPACING_PROPS[prop]) {
        inline(
          property.replace(/-(\w)/g, (_, letter: string) => letter.toUpperCase()),
          css,
        );
      }
    }
  }

  const { position, overflow, cursor, grow, minWidth, minHeight } = props;
  if (position !== undefined) {
    if (POSITIONS.has(position)) {
      className += ` hs-pos-${position}`;
    } else {
      inline('position', position as string);
    }
  }

  if (overflow !== undefined) {
    if (OVERFLOWS.has(overflow)) {
      className += ` hs-ov-${overflow}`;
    } else {
      inline('overflow', overflow as string);
    }
  }

  if (cursor !== undefined) {
    if (CURSORS.has(cursor)) {
      className += ` hs-cur-${cursor}`;
    } else {
      inline('cursor', cursor as string);
    }
  }

  if (grow !== undefined) {
    if (grow === 0 || grow === 1) {
      className += ` hs-grow-${grow}`;
    } else {
      inline('flexGrow', grow as number);
    }
  }

  if (minWidth === 0) {
    className += ' hs-minw-0';
  } else if (minWidth !== undefined) {
    inline('minWidth', sizeCss(minWidth));
  }

  if (minHeight === 0) {
    className += ' hs-minh-0';
  } else if (minHeight !== undefined) {
    inline('minHeight', sizeCss(minHeight));
  }

  for (const name of ['width', 'maxWidth', 'height', 'maxHeight'] as const) {
    if (props[name] !== undefined) {
      inline(name, sizeCss(props[name]));
    }
  }

  // Colors: a token has a class; any other CSS color is inline.
  for (const [name, abbreviation, property] of [
    ['color', 'c', 'color'],
    ['background', 'bg', 'background'],
  ] as const) {
    const value = props[name] as string | undefined;
    if (value === undefined) {
      continue;
    }

    if (PALETTE.has(value) || value === 'transparent') {
      className += ` hs-${abbreviation}-${kebab(value)}`;
    } else {
      inline(property, value);
    }
  }

  if (typeof props.radius === 'string' && RADII.has(props.radius)) {
    className += ` hs-r-${kebab(props.radius)}`;
  }

  if (typeof props.shadow === 'string' && SHADOWS.has(props.shadow)) {
    className += ` hs-sh-${kebab(props.shadow)}`;
  }

  if (typeof props.zIndex === 'string' && LAYERS.has(props.zIndex)) {
    className += ` hs-z-${kebab(props.zIndex)}`;
  }

  if (typeof props.typography === 'string' && TYPE.has(props.typography)) {
    className += ` hs-t-${kebab(props.typography)}`;
  }

  for (const [name, side] of [
    ['border', ''],
    ['borderTop', 't'],
    ['borderRight', 'r'],
    ['borderBottom', 'b'],
    ['borderLeft', 'l'],
  ] as const) {
    const value = props[name];
    if (value === true) {
      className += ` hs-bd${side}-border`;
    } else if (typeof value === 'string' && (PALETTE.has(value) || value === 'transparent')) {
      className += ` hs-bd${side}-${kebab(value)}`;
    }
  }

  // A press is what an element with an `onClick` is for.
  if ('onClick' in props && props.onClick !== undefined) {
    className += ' hs-clickable';
  }

  for (const piece of extra) {
    className += ` ${piece}`;
  }

  return { className, style };
}

/** `props` without the names that are the primitive's own. */
function rest(props: Record<string, unknown>) {
  const out: Record<string, unknown> = {};
  for (const key in props) {
    if (!SYSTEM.has(key)) {
      out[key] = props[key];
    }
  }

  return out;
}

function draw(
  props: Record<string, unknown>,
  tag: ElementType,
  extra: string[],
  extraStyle?: Record<string, string | number | undefined>,
): ReactElement {
  const { className, style } = resolve(props, extra);
  const Tag = tag;
  const classes =
    `${className}${typeof props.className === 'string' ? ` ${props.className}` : ''}`.trim();

  const own =
    style || extraStyle || props.style
      ? { ...extraStyle, ...style, ...(props.style as object) }
      : undefined;

  return <Tag {...rest(props)} className={classes || undefined} style={own} />;
}

/** An element with the design system's props: spacing, size, position, colors, radius, shadow, borders, layers. */
export function Box<E extends ElementType = 'div'>({ as, ...props }: BoxProps<E>): ReactElement {
  return draw(props as Record<string, unknown>, as ?? 'div', []);
}

/** A `Box` that lays its children out in a row or a column. */
export function Flex<E extends ElementType = 'div'>({ as, ...props }: FlexProps<E>): ReactElement {
  const { direction, center, centerMain, centerCross, justify, align, inline } =
    props as FlexSystem;

  const extra = [inline === true ? 'hs-d-inline-flex' : 'hs-d-flex'];
  let style: Record<string, string | number | undefined> | undefined;
  const put = (name: string, value: string) => {
    (style ??= {})[name] = value;
  };

  if (direction !== undefined) {
    if (DIRECTIONS.has(direction)) {
      extra.push(`hs-fd-${direction}`);
    } else {
      put('flexDirection', direction);
    }
  }

  // Centering wins over `align` and `justify`, as it always did.
  const cross = center === true || centerCross === true ? 'center' : align;
  if (cross !== undefined) {
    if (ALIGNS.has(cross)) {
      extra.push(`hs-ai-${cross}`);
    } else {
      put('alignItems', cross as string);
    }
  }

  const main = center === true || centerMain === true ? 'center' : justify;
  if (main !== undefined) {
    if (JUSTIFIES.has(main)) {
      extra.push(`hs-jc-${main}`);
    } else {
      put('justifyContent', main as string);
    }
  }

  return draw(props as Record<string, unknown>, as ?? 'div', extra, style);
}

/** A `Box` that lays its children out on a grid: a number of equal columns, or the sizes of the columns. */
export function Grid<E extends ElementType = 'div'>({ as, ...props }: GridProps<E>): ReactElement {
  const { columns } = props as GridSystem;
  const style: Record<string, string | number | undefined> = {};
  if (columns !== undefined) {
    const [first, second = '1fr'] = columns;
    const unit = (size: string | number) => (typeof size === 'number' ? `${size}px` : size);
    style.gridTemplateColumns = Array.isArray(second)
      ? (second as (string | number)[]).map(unit).join(' ')
      : `repeat(${first}, ${unit(second as string | number)})`;
  }

  return draw(props as Record<string, unknown>, as ?? 'div', ['hs-d-grid'], style);
}

/** Text in one of the theme's type variants. */
export function Typography<E extends ElementType = 'span'>({
  as,
  ...props
}: TypographyProps<E>): ReactElement {
  const { variant = 'default', align, noWrap, uppercase, textOverflow } = props as TypographySystem;
  const extra = [`hs-t-${kebab(variant)}`];
  const style: Record<string, string | number | undefined> = {};
  if (align !== undefined) {
    if (TEXT_ALIGNS.has(align)) {
      extra.push(`hs-ta-${align}`);
    } else {
      style.textAlign = align as string;
    }
  }

  if (noWrap === true) {
    extra.push('hs-nowrap');
  }

  if (uppercase === true) {
    extra.push('hs-upper');
  }

  if (textOverflow === 'ellipsis') {
    extra.push('hs-to-ellipsis');
  } else if (textOverflow === 'clip') {
    extra.push('hs-to-clip');
  } else if (textOverflow !== undefined) {
    style.textOverflow = textOverflow as string;
  }

  return draw(props as Record<string, unknown>, as ?? 'span', extra, style);
}

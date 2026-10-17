/** @jsxImportSource @emotion/react */
import type { Interpolation, Theme } from '@emotion/react';
import { Box, type BoxProps } from '../style/prim.tsx';
import type { ElementType } from 'react';
import { iconButtonSize, iconButtonStyles } from './icon-button-styles.ts';

/** The round button every tile, drawer, stepper and calendar uses: a circle in the surface color
 * with the shared hover, disabled and state looks (`data-active`, `data-feedback`, `data-primary`).
 * It is a `button`, or whatever `as` says (the tile's animated one passes `motion.button`), and
 * takes that element's own props. A `css` of its own is added to the shared look, not instead of it. */
export function RoundButton<E extends ElementType = 'button'>({
  size,
  css,
  ...props
}: BoxProps<E> & {
  /** The diameter in px. Defaults to the density's usual icon circle. */
  size?: number;
  css?: Interpolation<Theme>;
}) {
  const own = {
    as: 'button',
    type: 'button',
    ...(size !== undefined ? { width: size, height: size } : {}),
    // A size in `css` would outrank the `width` and `height` props, so the default one is only
    // added when there is no size of its own.
    css: [iconButtonStyles, size === undefined ? iconButtonSize : null, css ?? null],
    ...props,
  } as BoxProps<ElementType>;

  return <Box {...own} />;
}

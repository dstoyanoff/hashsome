/** @jsxImportSource @emotion/react */
import type { Interpolation, Theme } from '@emotion/react';
import { Flex, type FlexProps } from '../style/prim.tsx';
import type { ElementType } from 'react';

/** A button with the browser's look removed: no border, no background, no padding, text on the
 * left, and a pointer. Everything else is the element's own and e-prim's props (it is a `Flex`), so a clickable row,
 * card or chip is `<PlainButton gap={…} …>` with the look it wants. Renders a `button`, or what
 * `as` says (`motion.button`). A `css` of its own is added to the reset, not instead of it. */
export function PlainButton<E extends ElementType = 'button'>({
  css,
  ...props
}: FlexProps<E> & { css?: Interpolation<Theme> }) {
  const own = {
    as: 'button',
    type: 'button',
    p: 0,
    cursor: 'pointer',
    css: [{ background: 'none', textAlign: 'left' }, css ?? null],
    ...props,
  } as FlexProps<ElementType>;

  return <Flex {...own} />;
}

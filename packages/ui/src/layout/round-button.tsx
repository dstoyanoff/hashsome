import type { ComponentPropsWithRef, CSSProperties, ElementType } from 'react';
import { cx } from '../style/cx.ts';
import styles from './round-button.module.css';

/** The round button every tile, drawer, stepper and calendar uses: a circle in the surface color
 * with the shared hover, disabled and state looks (`data-active`, `data-feedback`, `data-primary`).
 * It is a `button`, or whatever `as` says (the tile's animated one passes `motion.button`), and
 * takes that element's own props; a `className` of its own is added to the shared look and wins over it. */
export function RoundButton<E extends ElementType = 'button'>({
  as,
  size,
  className,
  style,
  ...props
}: { as?: E; size?: number } & Omit<ComponentPropsWithRef<E>, 'as'>) {
  const Tag: ElementType = as ?? 'button';
  const own =
    size === undefined ? style : { ...(style as CSSProperties), '--round-size': `${size}px` };
  return (
    <Tag
      type="button"
      className={cx(styles.round, className as string | undefined)}
      style={own}
      {...props}
    />
  );
}

import type { ComponentPropsWithRef, ElementType } from 'react';
import { cx } from '../style/cx.ts';
import styles from './plain-button.module.css';

/** A button with the browser's look removed: no border, no background, no padding, text on the
 * left, and a pointer. The look of a clickable row, card or chip is the caller's own `className`
 * (it wins over the reset). Renders a `button`, or what `as` says (`motion.button`). */
export function PlainButton<E extends ElementType = 'button'>({
  as,
  className,
  ...props
}: { as?: E } & Omit<ComponentPropsWithRef<E>, 'as'>) {
  const Tag: ElementType = as ?? 'button';
  return (
    <Tag type="button" className={cx(styles.plain, className as string | undefined)} {...props} />
  );
}

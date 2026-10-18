import { cx } from '../style/cx.ts';
import styles from './hold-progress.module.css';
import { HOLD_MS } from './use-hold.ts';

export interface HoldProgressProps {
  /** Which edge of the card the line runs along. Default `top`. */
  edge?: 'top' | 'bottom';

  /** A literal CSS color for the line, alpha included. Left out, a half-faded muted text color. */
  color?: string;
}

/** The thin line along a card's edge that fills while a press is being timed as a hold. */
export function HoldProgress({ edge = 'top', color }: HoldProgressProps) {
  return (
    <span
      // A cue the user waits for: it keeps moving when `?motion=reduced` turns the rest off.
      data-keep-motion=""
      className={cx(styles.line, styles[edge], !color && styles.muted)}
      style={{ animationDuration: `${HOLD_MS}ms`, ...(color ? { backgroundColor: color } : {}) }}
    />
  );
}

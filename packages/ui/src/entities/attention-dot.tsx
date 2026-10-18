import { cx } from '../style/cx.ts';
import styles from './attention-dot.module.css';

/** The dot at the corner of a nav item: its ring is the rail's own color, so it reads as cut out of the button. */
export function AttentionDot({ level }: { level: 'notice' | 'urgent' }) {
  return <span aria-hidden="true" className={cx(styles.dot, styles[level])} />;
}

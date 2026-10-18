import type { ReactNode } from 'react';
import { useFullMotion } from '../motion/mode.ts';
import styles from './reveal.module.css';

/** How many items are staggered at all. */
const STAGGERED = 8;

/**
 * Fades its content in as it appears, rising a few pixels, a little later for each one after the first (the first handful only, so a
 * long list does not take long to arrive, and the rest are not animated at all). It only moves what is drawn inside it, never the
 * room it takes, so nothing around it shifts. By CSS, in full motion only: elsewhere it just appears.
 */
export function Reveal({ index = 0, children }: { index?: number; children: ReactNode }) {
  const full = useFullMotion();
  // Past the staggered ones, a long list is just there: a fade of every row is work on a slow display, for rows that are mostly below the fold.
  if (!full || index >= STAGGERED) {
    return <>{children}</>;
  }

  return (
    <div className={styles.reveal} style={{ '--i': index } as React.CSSProperties}>
      {children}
    </div>
  );
}

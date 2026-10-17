import type { ReactNode } from 'react';
import styles from './grid.module.css';

export interface GridProps {
  /** Number of equal columns. Default 2. */
  columns?: number;

  /** Tiles (or any content) to lay out, one per cell. */
  children: ReactNode;
}

/** A grid of equal-width columns for tiles; cells share the width and never overflow it. */
export function Grid({ columns = 2, children }: GridProps) {
  return (
    <div
      className={styles.grid}
      style={{ gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))` }}
    >
      {children}
    </div>
  );
}

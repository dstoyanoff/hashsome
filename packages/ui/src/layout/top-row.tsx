import type { ReactNode } from 'react';
import styles from './top-row.module.css';

export interface TopRowProps {
  /** What goes in the row: the date, the weather, the clock. */
  children: ReactNode;
}

/** The row along the top of a page you build yourself instead of with `TopBar`: its pieces (`DateChip`,
 * `WeatherChip`, `Clock`, `SystemStatus`) in a line at the right, in the height the grid gives the top
 * row, with no padding of its own. */
export function TopRow({ children }: TopRowProps) {
  return <div className={styles.row}>{children}</div>;
}

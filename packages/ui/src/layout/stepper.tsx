import type { ReactNode } from 'react';
import styles from './stepper.module.css';

/** A pill holding a minus button, a value and a plus button. The buttons inside lose their own
 * background and shrink to fit the pill. */
export function Stepper({ children }: { children: ReactNode }) {
  return <div className={styles.stepper}>{children}</div>;
}

/** The value between a `Stepper`'s buttons. */
export function StepperValue({ children }: { children: ReactNode }) {
  return <span className={styles.value}>{children}</span>;
}

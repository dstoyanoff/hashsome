import { m } from '../motion/index.tsx';
import styles from './check-mark.module.css';

/** A check that draws itself, in the text color of what it is in. It plays once, as it appears; with
 * reduced motion it appears drawn. Announced as "Done". */
export function CheckMark({ size = 22 }: { size?: number }) {
  return (
    <span role="status" aria-label="Done" className={styles.mark}>
      <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true">
        <m.path
          d="M6 12.5 L10.5 17 L18 8"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.6"
          strokeLinecap="round"
          strokeLinejoin="round"
          initial={{ pathLength: 0 }}
          animate={{ pathLength: 1 }}
          transition={{ duration: 0.3, delay: 0.05, ease: 'easeOut' }}
        />
      </svg>
    </span>
  );
}

import type { RadiusName } from '../style/names.ts';
import styles from './skeleton.module.css';

/** A placeholder block for something that is on its way, in the size it will have, so what is around
 * it does not move when the real thing arrives. It pulses gently, and holds still where the person
 * asks their device for less motion. Decorative: say what is loading around it, with `aria-busy`. */
export function Skeleton({
  width,
  height,
  radius = 'small',
}: {
  /** px, or any CSS length. */
  width: number | string;
  height: number | string;
  radius?: RadiusName;
}) {
  return (
    <div
      aria-hidden="true"
      className={styles.block}
      style={{ width, height, borderRadius: `var(--hs-radius-${radius})` }}
    >
      <span className={styles.pulse} />
    </div>
  );
}

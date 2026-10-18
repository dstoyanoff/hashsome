import { LoaderCircle } from 'lucide-react';
import { Icon } from '../icon.tsx';
import { kebab, type PaletteName } from '../style/names.ts';
import styles from './spinner.module.css';

/** A turning ring: something is in progress and will not say how long it takes. Kept turning when the
 * app is set to reduced motion (`data-keep-motion`): a spinner that stopped would look stuck, and one
 * small turning icon costs next to nothing. */
export function Spinner({ size = 16, label = 'Loading' }: { size?: number; label?: string }) {
  return (
    <span role="status" aria-label={label} data-keep-motion className={styles.spinner}>
      <Icon size={size}>
        <LoaderCircle />
      </Icon>
    </span>
  );
}

/** A ring that turns around the edge of a round button (give the button `position: relative`): the
 * button is waiting for an answer and will not take another press. Kept turning under reduced motion
 * for the same reason `Spinner` is. */
export function SpinnerRing({
  label = 'Loading',
  color = 'accent',
}: {
  label?: string;

  /** A palette color: the theme's accent by default. Pass `accentText` for a button that is itself accent-colored (or filled with a color of the app's own). */
  color?: PaletteName | 'transparent';
}) {
  return (
    <span
      role="status"
      aria-label={label}
      data-keep-motion
      className={styles.ring}
      style={color === 'accent' ? undefined : { color: `var(--hs-${kebab(color)})` }}
    >
      <svg viewBox="0 0 100 100" width="100%" height="100%" aria-hidden="true">
        <circle
          cx="50"
          cy="50"
          r="47"
          fill="none"
          stroke="currentColor"
          strokeWidth="5"
          strokeLinecap="round"
          strokeDasharray="75 221"
        />
      </svg>
    </span>
  );
}

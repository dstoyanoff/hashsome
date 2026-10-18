import { useLayoutEffect, useRef } from 'react';
import { useFullMotion } from '../motion/mode.ts';
import styles from './animated-number.module.css';

export interface AnimatedNumberProps {
  /** The number to show. When it changes, what is shown moves to it instead of jumping. */
  value: number;

  /** How a number is written, with its unit: `(n) => \`${n.toFixed(1)} W\``. Called for every step of the move, with a number between the old value and the new one, so it should round. Default: the number with as many decimals as `value` has. */
  format?: (n: number) => string;

  /** Seconds the move takes. Default 0.6. */
  duration?: number;
}

/** How many decimals a number is written with: `40.1` has one. */
export function decimalsOf(value: number): number {
  return Number.isFinite(value) ? (String(value).split('.')[1] ?? '').length : 0;
}

/** A number that moves to its new value instead of jumping to it: a power reading that changes, a
 * temperature. The first value is shown at once, and a new one is moved to from what is shown now, even
 * if it has not arrived yet; the text is changed directly (no render per step), so many of them cost
 * little, and none moves when the app is set to reduced motion. */
export function AnimatedNumber({ value, format, duration = 0.6 }: AnimatedNumberProps) {
  const text = useRef<HTMLSpanElement>(null);
  const shown = useRef(value);
  // What was asked for last, kept for the move, which must not start over because a caller's function is new.
  const write = useRef((n: number) => String(n));
  const full = useFullMotion();
  const decimals = decimalsOf(value);
  const written = (n: number) => (format ? format(n) : n.toFixed(decimals));
  useLayoutEffect(() => {
    write.current = written;
  });

  // Before the first paint of a new value, so the final value is never seen for a frame first.
  useLayoutEffect(() => {
    const node = text.current;
    const from = shown.current;
    if (!node || from === value || !Number.isFinite(from) || !Number.isFinite(value)) {
      shown.current = value;
      return;
    }

    // Where nothing moves: it is simply the new value.
    if (!full) {
      shown.current = value;
      node.textContent = write.current(value);
      return;
    }

    node.textContent = write.current(from);
    const started = performance.now();
    let frame = 0;
    const step = () => {
      const t = Math.min(1, (performance.now() - started) / (duration * 1000));
      // Easing out, as a curve with no library: fast at first, settling into the value.
      const eased = 1 - (1 - t) ** 3;
      shown.current = t === 1 ? value : from + (value - from) * eased;
      node.textContent = write.current(shown.current);
      if (t < 1) {
        frame = requestAnimationFrame(step);
      }
    };

    frame = requestAnimationFrame(step);
    return () => cancelAnimationFrame(frame);
  }, [value, duration, full]);

  return (
    <span ref={text} className={styles.number}>
      {written(value)}
    </span>
  );
}

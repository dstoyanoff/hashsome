import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { cx } from '../style/cx.ts';
import styles from './marquee-text.module.css';
import { useFullMotion } from '../motion/mode.ts';

/** How fast the text rolls, in px a second. */
const SPEED = 36;

/** The shortest a roll takes (there and back, resting at each end), in seconds: a text that is only a little too long does not dart about. */
const MIN_SECONDS = 3;

/** The share of a roll in which the text moves: it rests for the rest, at each end. */
const MOVING = 0.76;

/**
 * One line of text that fits the width it is given: when it is too long it rolls sideways, resting at
 * each end, so all of it can be read; when it fits it just sits there. Whoever it is in decides the
 * width (a `max-width` on it or on a parent), so a long title can never push its tile out of shape,
 * whatever it holds (an emoji at the end included). Where the person asks their device for less
 * motion it stays still and ends in an ellipsis.
 */
export function MarqueeText({ children }: { children: ReactNode }) {
  const outer = useRef<HTMLSpanElement>(null);
  const inner = useRef<HTMLSpanElement>(null);
  const [overflow, setOverflow] = useState(0);
  const full = useFullMotion();
  const rolling = overflow > 0 && full;

  useEffect(() => {
    const frame = outer.current;
    const text = inner.current;
    if (!frame || !text) {
      return;
    }

    const measure = () => setOverflow(Math.max(0, Math.ceil(text.scrollWidth - frame.clientWidth)));
    measure();
    if (typeof ResizeObserver === 'undefined') {
      return;
    }

    // The frame changes with the room it has; the text with what it says.
    const watch = new ResizeObserver(measure);
    watch.observe(frame);
    watch.observe(text);
    return () => watch.disconnect();
    // The text's element is another one once it rolls (see below), which is the one to watch.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [rolling]);

  // The time to move the whole way, at SPEED, and the roll that rests a little at each end around it (see `marquee-text.module.css`).
  const seconds = Math.max(MIN_SECONDS, overflow / SPEED / MOVING);
  return (
    <span ref={outer} className={cx(styles.frame, rolling && styles.rolling)}>
      {/* The roll is only on text that rolls: a list has a title and a line under it in every row, and nearly all of them fit. */}
      <span
        ref={inner}
        className={cx(styles.text, rolling && styles.roll)}
        style={
          rolling
            ? ({ '--x': `${-overflow}px`, '--seconds': `${seconds}s` } as CSSProperties)
            : undefined
        }
      >
        {children}
      </span>
    </span>
  );
}

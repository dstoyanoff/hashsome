/** @jsxImportSource @emotion/react */
import { motion } from 'motion/react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useStill } from './use-still.ts';

/** How fast the text rolls, in px a second. */
const SPEED = 36;

/** How long it waits at each end, in seconds. */
const PAUSE = 1.4;

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
  const still = useStill();
  const rolling = overflow > 0 && !still;

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

  const seconds = overflow / SPEED;
  return (
    <span
      ref={outer}
      css={{
        display: 'block',
        // A line height of its own, not the font's: an emoji comes from another font with a taller
        // line, and would make a row with one in its title taller than the rest.
        lineHeight: 1.3,
        minWidth: 0,
        maxWidth: '100%',
        overflow: 'hidden',
        whiteSpace: 'nowrap',
        textOverflow: rolling ? 'clip' : 'ellipsis',
      }}
    >
      {/* A motion element only for text that rolls: a list has a title and a line under it in every row, and
          nearly all of them fit. */}
      {rolling ? (
        <motion.span
          ref={inner}
          css={{ display: 'inline-block' }}
          animate={{ x: [0, 0, -overflow, -overflow, 0] }}
          transition={{
            duration: 2 * seconds + 2 * PAUSE,
            times: [
              0,
              PAUSE / (2 * seconds + 2 * PAUSE),
              (PAUSE + seconds) / (2 * seconds + 2 * PAUSE),
              (2 * PAUSE + seconds) / (2 * seconds + 2 * PAUSE),
              1,
            ],
            ease: 'linear',
            repeat: Infinity,
          }}
        >
          {children}
        </motion.span>
      ) : (
        <span ref={inner} css={{ display: 'inline-block' }}>
          {children}
        </span>
      )}
    </span>
  );
}

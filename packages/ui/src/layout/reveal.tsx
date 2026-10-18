import { m } from '../motion/index.tsx';
import type { ReactNode } from 'react';
import { useStill } from './use-still.ts';

/** How much later each following item starts, in seconds, and how many are staggered at all. */
const STAGGER = 0.03;
const STAGGERED = 8;

/**
 * Fades its content in as it appears, rising a few pixels, a little later for each one after the
 * first (the first handful only, so a long list does not take long to arrive, and the rest are not animated at all). It only moves what is
 * drawn inside it, never the room it takes, so nothing around it shifts. Where the person asks their
 * device for less motion it just appears.
 */
export function Reveal({ index = 0, children }: { index?: number; children: ReactNode }) {
  const still = useStill();
  // Past the staggered ones, a long list is just there: a fade of every row is work on a slow display, for rows
  // that are mostly below the fold.
  if (still || index >= STAGGERED) {
    return <>{children}</>;
  }

  return (
    <m.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.22, ease: 'easeOut', delay: Math.min(index, STAGGERED) * STAGGER }}
    >
      {children}
    </m.div>
  );
}

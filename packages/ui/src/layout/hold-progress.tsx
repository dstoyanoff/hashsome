/** @jsxImportSource @emotion/react */
import { keyframes } from '@emotion/react';
import { Box } from '../style/prim.tsx';
import { HOLD_MS } from './use-hold.ts';

/** Grows the line from its left end by scaling it, which the browser animates on its own, without
 * the page's JavaScript or a layout for each frame: on a slow display, animating the width is what
 * made the hold look stuck. */
const fill = keyframes({
  from: { transform: 'scaleX(0)' },
  to: { transform: 'scaleX(1)' },
});

export interface HoldProgressProps {
  /** Which edge of the card the line runs along. Default `top`. */
  edge?: 'top' | 'bottom';

  /** A literal CSS color for the line, alpha included. Left out, a half-faded muted text color. */
  color?: string;
}

/** The thin line along a card's edge that fills while a press is being timed as a hold. */
export function HoldProgress({ edge = 'top', color }: HoldProgressProps) {
  return (
    <Box
      as="span"
      // A cue the user waits for: it keeps moving when `?motion=reduced` turns the rest off.
      data-keep-motion=""
      position="absolute"
      width="100%"
      height={2}
      {...(color ? {} : { background: 'textMuted' as const })}
      css={{
        [edge]: 0,
        left: 0,
        opacity: color ? 1 : 0.5,
        ...(color ? { backgroundColor: color } : {}),
        pointerEvents: 'none',
        transformOrigin: 'left center',
        willChange: 'transform',
        animation: `${fill} ${HOLD_MS}ms linear forwards`,
      }}
    />
  );
}

/** @jsxImportSource @emotion/react */
import { Box, type BoxProps } from '../style/prim.tsx';
import { motion } from 'motion/react';
import { useStill } from './use-still.ts';

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
  radius?: BoxProps['radius'];
}) {
  const still = useStill();
  return (
    <Box
      aria-hidden="true"
      background="surfaceRaised"
      radius={radius}
      css={{ width, height, flex: 'none', overflow: 'hidden' }}
    >
      <motion.span
        css={{ display: 'block', width: '100%', height: '100%', background: 'currentColor' }}
        initial={{ opacity: 0.1 }}
        {...(still
          ? {}
          : {
              animate: { opacity: [0.04, 0.14, 0.04] },
              transition: { duration: 1.4, repeat: Infinity, ease: 'easeInOut' },
            })}
      />
    </Box>
  );
}

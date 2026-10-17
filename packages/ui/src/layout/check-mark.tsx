/** @jsxImportSource @emotion/react */
import { Box } from '../style/prim.tsx';
import { motion } from 'motion/react';

/** A check that draws itself, in the text color of what it is in. It plays once, as it appears; with
 * reduced motion it appears drawn. Announced as "Done". */
export function CheckMark({ size = 22 }: { size?: number }) {
  return (
    <Box as="span" role="status" aria-label="Done" css={{ display: 'grid', color: 'inherit' }}>
      <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true">
        <motion.path
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
    </Box>
  );
}

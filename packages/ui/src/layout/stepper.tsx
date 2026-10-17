/** @jsxImportSource @emotion/react */
import { Flex, Typography } from '../style/prim.tsx';
import type { ReactNode } from 'react';

/** A pill holding a minus button, a value and a plus button. The buttons inside lose their own
 * background and shrink to fit the pill. */
export function Stepper({ children }: { children: ReactNode }) {
  return (
    <Flex
      align="center"
      background="surfaceRaised"
      radius="full"
      height={32}
      gap={1}
      px={1}
      css={{ '& button': { background: 'none', width: 28, height: 28 } }}
    >
      {children}
    </Flex>
  );
}

/** The value between a `Stepper`'s buttons. */
export function StepperValue({ children }: { children: ReactNode }) {
  return (
    <Typography
      as="span"
      variant="body"
      minWidth="4.2em"
      align="center"
      css={{ fontVariantNumeric: 'tabular-nums' }}
    >
      {children}
    </Typography>
  );
}

/** @jsxImportSource @emotion/react */
import { Flex } from '../style/prim.tsx';
import type { ReactNode } from 'react';
import { TOP_ROW } from '../theme/grid.ts';

export interface TopRowProps {
  /** What goes in the row: the date, the weather, the clock. */
  children: ReactNode;
}

/** The row along the top of a page you build yourself instead of with `TopBar`: its pieces (`DateChip`,
 * `WeatherChip`, `Clock`, `SystemStatus`) in a line at the right, in the height the grid gives the top
 * row, with no padding of its own. */
export function TopRow({ children }: TopRowProps) {
  return (
    <Flex align="center" justify="flex-end" gap={3} css={{ minHeight: TOP_ROW }}>
      {children}
    </Flex>
  );
}

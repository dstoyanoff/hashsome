import { Grid as EPrimGrid } from '../style/prim.tsx';
import type { ReactNode } from 'react';

export interface GridProps {
  /** Number of equal columns. Default 2. */
  columns?: number;

  /** Tiles (or any content) to lay out, one per cell. */
  children: ReactNode;
}

/** A grid of equal-width columns for tiles; cells share the width and never overflow it. */
export function Grid({ columns = 2, children }: GridProps) {
  return (
    <EPrimGrid columns={[columns, 'minmax(0, 1fr)']} gap={3}>
      {children}
    </EPrimGrid>
  );
}

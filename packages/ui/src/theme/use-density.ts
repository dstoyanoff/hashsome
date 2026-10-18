import { createContext, useContext } from 'react';
import { DENSITY, UNITS_PER_SPACE, type DensityTokens } from './density.ts';

/** The density's sizes in effect, for the few things that need them as numbers (the grid's arithmetic), where everything
 * else reads them as the custom properties `--hs-space`, `--hs-unit`, `--hs-tile-height`, ... */
export const DensityContext = createContext<DensityTokens>(DENSITY.comfortable);

export function useDensity(): DensityTokens {
  return useContext(DensityContext);
}

/** The theme's spacing unit in px: a third of a space, so `gap={3}` is one space. */
export function useSpacingUnit(): number {
  return useDensity().space / UNITS_PER_SPACE;
}

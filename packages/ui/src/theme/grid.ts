import { UNITS_PER_SPACE } from './density.ts';

/** The icon badge a tile starts with, in px: the one size in a tile that does not scale with density. */
export const BADGE = 32;

/** The height of a room header, in px. */
export const HEADER = 32;

/** The height of the top row, in px: the clock's line and the tallest things beside it (the switcher, the
 * scene buttons), with no padding of its own, so its content sits as far from the page's edge as
 * everything else does. Sizes that do not scale with density are multiples of 8, so they are whole
 * modules in both (4px and 2.67px). */
export const TOP_ROW = 40;

/** The vertical grid every card sits on. The module is the theme's spacing unit (a third of a
 * space: 4px in comfortable density, 2.67px in compact), so the gap between cards is always 3
 * modules, and a tile and a header are whole numbers of them in either density. Sizes are in px;
 * `pitch` is a card's height plus the gap that follows it, and a card spanning n pitches is n heights
 * and n-1 gaps tall. */
export interface GridMetrics {
  module: number;
  gap: number;
  tile: number;
  header: number;
  tilePitch: number;
  headerPitch: number;
}

export function gridMetrics(space: number): GridMetrics {
  const module = space / UNITS_PER_SPACE;
  // A tile is its badge between two spacing-unit-2 paddings.
  const tile = BADGE + 4 * module;

  return {
    module,
    gap: space,
    tile,
    header: HEADER,
    tilePitch: tile + space,
    headerPitch: HEADER + space,
  };
}

/** Whether a box's top edge, measured from the grid's origin, or its height is off the module grid. Browsers round to the pixel, so a little under a pixel is still on it. */
export function offGrid(top: number, height: number, module: number): boolean {
  const tolerance = Math.max(0.5, module * 0.15);
  const off = (value: number) => {
    const rest = Math.abs(value) % module;
    return Math.min(rest, module - rest) > tolerance;
  };

  return off(top) || off(height);
}

/** How a height's leftover (what is under one module after as many whole ones as fit) is shared between the top and the bottom padding: half each, in whole pixels, the odd one to the bottom. `available` is the height inside the page's padding. */
export function centeringOffsets(
  available: number,
  module: number,
): { top: number; bottom: number } {
  const leftover = available > 0 ? Math.floor(available % module) : 0;
  const top = Math.floor(leftover / 2);

  return { top, bottom: leftover - top };
}

/** The width of the page navigation rail (`NavRail`), whose icons are centered in it. Things that sit at the
 * left edge of the screen over a dashboard (the debug button) are centered on the same line. */
export const NAV_RAIL = 76;

/** The height of the page navigation bar (`NavBar`), the rail on its side along the bottom of the screen: the
 * 44px buttons with 10px above and below them. */
export const NAV_BAR = 64;

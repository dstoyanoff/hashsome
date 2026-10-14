/** @jsxImportSource @emotion/react */
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useState } from 'react';
import { useLocation, useOutlet } from 'react-router';
import type { NavItem } from '../entities/nav-rail.tsx';

export interface AnimatedOutletProps {
  /** The dashboard's pages in the order its nav lists them (the same `items` as `NavRail`, `NavDock` or `NavBar`). A page then slides in from the side it sits on: a later page from the right, an earlier one from the left. Left out, pages only fade. */
  items?: NavItem[];

  /** The path `items` are relative to, e.g. `/bathroom`. Needed with `items`. */
  base?: string;
}

/** How far a page slides, in px, as it comes and goes. */
const SLIDE = 24;

/** The index of the item whose page `pathname` is, or -1. */
function indexAt(pathname: string, items: NavItem[] | undefined, base: string | undefined) {
  if (!items || base === undefined) {
    return -1;
  }

  const here = pathname.replace(/\/$/, '') || '/';
  return items.findIndex((item) => (item.to ? `${base}/${item.to}` : base) === here);
}

/**
 * A dashboard layout's `<Outlet />` with a transition between its pages: the page that was showing
 * fades out (sliding a little toward the side it was on), then the new one fades in from the side
 * it is on. It is a drop-in for `Outlet` in a layout route, and does nothing the first time a page
 * shows or for a visitor who prefers reduced motion.
 */
export function AnimatedOutlet({ items, base }: AnimatedOutletProps) {
  const outlet = useOutlet();
  const { pathname } = useLocation();
  const still = useReducedMotion();
  const index = indexAt(pathname, items, base);
  // Moving to a later page is +1, to an earlier one -1; with no order to go by, 0 is just a fade.
  const [seen, setSeen] = useState({ index, direction: 0 });
  if (seen.index !== index) {
    setSeen({
      index,
      direction: index === -1 || seen.index === -1 ? 0 : Math.sign(index - seen.index),
    });
  }

  const seconds = still ? 0 : 0.14;
  const slide = still ? 0 : SLIDE;
  return (
    <AnimatePresence mode="wait" initial={false} custom={seen.direction}>
      <motion.div
        key={pathname}
        custom={seen.direction}
        variants={{
          enter: (side: number) => ({ opacity: 0, x: side * slide }),
          show: { opacity: 1, x: 0 },
          exit: (side: number) => ({ opacity: 0, x: -side * slide }),
        }}
        initial="enter"
        animate="show"
        exit="exit"
        transition={{ duration: seconds, ease: 'easeOut' }}
        // A page fills the space it is given, as it does without the wrapper.
        css={{ display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0 }}
      >
        {outlet}
      </motion.div>
    </AnimatePresence>
  );
}

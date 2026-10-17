import { AnimatePresence, motion } from 'motion/react';
import { useState, type CSSProperties } from 'react';
import { useLocation, useOutlet } from 'react-router';
import type { NavItem } from '../entities/nav-rail.tsx';
import styles from './animated-outlet.module.css';
import { useStill } from './use-still.ts';

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
 *
 * A page longer than the screen just scrolls the `Page`. A page that is meant to fill the screen and scroll
 * inside itself (a player with its library, say) gives its root `flex-basis: 0` and `min-height: 0`, so
 * what is in it does not make the page taller; a `Board` already does.
 */
export function AnimatedOutlet({ items, base }: AnimatedOutletProps) {
  const outlet = useOutlet();
  const { pathname } = useLocation();
  const still = useStill();
  const index = indexAt(pathname, items, base);
  // Moving to a later page is +1, to an earlier one -1; with no order to go by, 0 is just a fade.
  const [seen, setSeen] = useState({ index, direction: 0 });
  if (seen.index !== index) {
    setSeen({
      index,
      direction: index === -1 || seen.index === -1 ? 0 : Math.sign(index - seen.index),
    });
  }

  // Nothing to animate: the page is there at once, rendered once. The exit-then-enter of the transition is two
  // renders of a page, which is the last thing a slow display needs for a transition that takes no time.
  // In `light` motion the page that comes still slides in from its side, by CSS (`animated-outlet.module.css`): once, with
  // no exit, so it is one render of the page.
  const [first] = useState(pathname);
  if (still) {
    return (
      <div
        key={pathname}
        className={styles.page}
        data-enter={pathname !== first}
        style={{ '--side': seen.direction * SLIDE + 'px' } as CSSProperties}
      >
        {outlet}
      </div>
    );
  }

  const seconds = 0.14;
  const slide = SLIDE;
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
        className={styles.page}
      >
        {outlet}
      </motion.div>
    </AnimatePresence>
  );
}

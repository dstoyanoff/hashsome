import { useState, type CSSProperties } from 'react';
import { useLocation, useOutlet } from 'react-router';
import type { NavItem } from '../entities/nav-rail.tsx';
import styles from './animated-outlet.module.css';

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
  const index = indexAt(pathname, items, base);
  const [first] = useState(pathname);
  // Moving to a later page is +1, to an earlier one -1; with no order to go by, 0 is just a fade.
  const [seen, setSeen] = useState({ index, direction: 0 });
  if (seen.index !== index) {
    setSeen({
      index,
      direction: index === -1 || seen.index === -1 ? 0 : Math.sign(index - seen.index),
    });
  }

  // The page is there at once, rendered once: where there is motion it slides in from its side by CSS (`animated-outlet.module.css`),
  // with no page leaving first, which is two renders of a page.
  return (
    <div
      key={pathname}
      className={styles.page}
      data-enter={pathname !== first}
      style={{ '--side': `${seen.direction * SLIDE}px` } as CSSProperties}
    >
      {outlet}
    </div>
  );
}

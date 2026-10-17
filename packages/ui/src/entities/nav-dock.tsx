import { usePageInset } from '../layout/page.tsx';
import { useSpacingUnit } from '../theme/use-density.ts';
import { NavItemLink } from './nav-item.tsx';
import styles from './nav-dock.module.css';
import { useIdleReturn, useIdleReturnDefault } from '../layout/use-idle-return.ts';
import { mainPath, type NavItem } from './nav-rail.tsx';

export interface NavDockProps {
  /** The pages to link to; `to` is relative to `base`. */
  items: NavItem[];

  /** Base path items are resolved against, e.g. `/home`. */
  base: string;

  /** How long, in ms, the display may be left alone on a page other than the main one (the item whose `to` is `''`) before it goes back to that page; `false` for never. Any touch, click, key or scroll starts the time again. Defaults to `HashsomeProvider`'s `idleReturn`, which is off unless the app turns it on. */
  idleReturn?: number | false;
}

/** Floating bottom pill for switching between a dashboard's pages. Must be rendered inside a
 * router. `position: fixed`, floating over the page; it reserves the space it takes in the
 * surrounding `Page`, which pads for it. On a phone with a home indicator it floats above the indicator. */
export function NavDock({ items, base, idleReturn }: NavDockProps) {
  const appDefault = useIdleReturnDefault();
  useIdleReturn({ to: mainPath(items, base), after: idleReturn ?? appDefault });
  // It floats `spacing(5)` above the edge, which is as much of a home indicator as it already clears.
  usePageInset('bottom', 88, 5 * useSpacingUnit());

  return (
    <nav aria-label="Pages" className={styles.dock}>
      {items.map((item) => (
        <NavItemLink key={item.to} item={item} base={base} round />
      ))}
    </nav>
  );
}

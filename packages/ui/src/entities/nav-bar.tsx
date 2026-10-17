import { usePageInset } from '../layout/page.tsx';
import { useIdleReturn, useIdleReturnDefault } from '../layout/use-idle-return.ts';
import { NAV_BAR } from '../theme/grid.ts';
import { NavItemLink } from './nav-item.tsx';
import styles from './nav-bar.module.css';
import { mainPath, type NavItem } from './nav-rail.tsx';

export interface NavBarProps {
  /** The pages to link to; `to` is relative to `base`. */
  items: NavItem[];

  /** Base path items are resolved against, e.g. `/home`. */
  base: string;

  /** How long, in ms, the display may be left alone on a page other than the main one (the item whose `to` is `''`) before it goes back to that page; `false` for never. Any touch, click, key or scroll starts the time again. Defaults to `HashsomeProvider`'s `idleReturn`, which is off unless the app turns it on. */
  idleReturn?: number | false;
}

/** The left rail's design along the bottom of the screen, for a small display where the rail would take too much
 * of the width: the same rail colour and rounded-square buttons (the open page lit), in a row at the middle of a bar
 * that runs from edge to edge, not floating like `NavDock`. Must be rendered inside a router. It is `position: fixed`
 * and reserves its height in the surrounding `Page`, which pads for it so content never sits under it. On a phone with
 * a home indicator the bar is taller by the indicator's height, and its buttons stay clear of it. */
export function NavBar({ items, base, idleReturn }: NavBarProps) {
  const appDefault = useIdleReturnDefault();
  useIdleReturn({ to: mainPath(items, base), after: idleReturn ?? appDefault });
  usePageInset('bottom', NAV_BAR);

  return (
    <nav aria-label="Pages" className={styles.bar}>
      {items.map((item) => (
        <NavItemLink key={item.to} item={item} base={base} />
      ))}
    </nav>
  );
}

import type { IconName } from '../icon-data.ts';
import { usePageInset } from '../layout/page.tsx';
import { NAV_RAIL } from '../theme/grid.ts';
import { NavItemLink } from './nav-item.tsx';
import styles from './nav-rail.module.css';
import { useIdleReturn, useIdleReturnDefault } from '../layout/use-idle-return.ts';

export interface NavItem {
  /** Route path relative to `base`, e.g. `''` (the dashboard's home) or `'lights'`. */
  to: string;
  label: string;
  icon: IconName;

  /** A dot on the item for something on that page that needs a look: `notice` (warm) or `urgent` (red). Nothing by default. */
  attention?: 'notice' | 'urgent';
}

/** The path of a dashboard's main page: the item with no path of its own, else its first item. */
export function mainPath(items: NavItem[], base: string): string {
  const main = items.find((item) => item.to === '') ?? items[0];
  return main?.to ? `${base}/${main.to}` : base;
}

export interface NavRailProps {
  /** The pages to link to; `to` is relative to `base`. */
  items: NavItem[];

  /** Base path items are resolved against, e.g. `/home`. */
  base: string;

  /** How long, in ms, the display may be left alone on a page other than the main one (the item whose `to` is `''`) before it goes back to that page; `false` for never. Any touch, click, key or scroll starts the time again. Defaults to `HashsomeProvider`'s `idleReturn`, which is off unless the app turns it on. */
  idleReturn?: number | false;
}

/** Fixed left sidebar for switching between a dashboard's pages. Must be rendered inside a
 * router. It is `position: fixed` and reserves its width in the surrounding `Page`, which pads
 * for it so content never sits under it. */
export function NavRail({ items, base, idleReturn }: NavRailProps) {
  const appDefault = useIdleReturnDefault();
  useIdleReturn({ to: mainPath(items, base), after: idleReturn ?? appDefault });
  usePageInset('left', NAV_RAIL);

  return (
    <nav aria-label="Pages" className={styles.rail}>
      {items.map((item) => (
        <NavItemLink key={item.to} item={item} base={base} />
      ))}
    </nav>
  );
}

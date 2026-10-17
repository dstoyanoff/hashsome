import { NavLink } from 'react-router';
import { Icon } from '../icon.tsx';
import { isInstalledApp } from '../layout/installed-app.ts';
import { cx } from '../style/cx.ts';
import { AttentionDot } from './attention-dot.tsx';
import type { NavItem } from './nav-rail.tsx';
import styles from './nav.module.css';

/** One page's button in a rail, bar or dock: a link to the page, lit while it is open, with a dot when something on it needs a look. */
export function NavItemLink({
  item,
  base,
  round = false,
}: {
  item: NavItem;
  base: string;

  /** A circle, for the floating dock; else a rounded square. */
  round?: boolean;
}) {
  return (
    <NavLink
      // In an installed app, switching pages replaces the one before it: there is no browser to go back with, and
      // the system's swipe from the edge of the screen would otherwise walk back through every page visited.
      replace={isInstalledApp()}
      to={item.to ? `${base}/${item.to}` : base}
      end
      aria-label={item.attention ? `${item.label}, needs attention` : item.label}
      title={item.label}
    >
      {({ isActive }) => (
        <span className={cx(styles.item, round && styles.round, isActive && styles.active)}>
          <Icon name={item.icon} size={22} />
          {item.attention ? <AttentionDot level={item.attention} /> : null}
        </span>
      )}
    </NavLink>
  );
}

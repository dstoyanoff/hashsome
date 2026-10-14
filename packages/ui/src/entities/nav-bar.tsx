/** @jsxImportSource @emotion/react */
import { Flex } from 'e-prim';
import { NavLink } from 'react-router';
import { Icon } from '../icon.tsx';
import { usePageInset } from '../layout/page.tsx';
import { useIdleReturn, useIdleReturnDefault } from '../layout/use-idle-return.ts';
import { NAV_BAR } from '../theme/grid.ts';
import { AttentionDot } from './attention-dot.tsx';
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
 * and reserves its height in the surrounding `Page`, which pads for it so content never sits under it. */
export function NavBar({ items, base, idleReturn }: NavBarProps) {
  const appDefault = useIdleReturnDefault();
  useIdleReturn({ to: mainPath(items, base), after: idleReturn ?? appDefault });
  usePageInset('bottom', NAV_BAR);

  return (
    <Flex
      as="nav"
      aria-label="Pages"
      align="center"
      justify="center"
      gap={2.5}
      background="rail"
      height={NAV_BAR}
      position="fixed"
      zIndex="nav"
      css={{ left: 0, right: 0, bottom: 0 }}
    >
      {items.map((item) => (
        <NavLink
          key={item.to}
          to={item.to ? `${base}/${item.to}` : base}
          end
          aria-label={item.attention ? `${item.label}, needs attention` : item.label}
          title={item.label}
        >
          {({ isActive }) => (
            <Flex
              as="span"
              align="center"
              justify="center"
              width={44}
              height={44}
              radius="chrome"
              cursor="pointer"
              position="relative"
              color={isActive ? 'accentText' : 'line'}
              {...(isActive ? { background: 'accent' } : {})}
            >
              <Icon name={item.icon} size={22} />
              {item.attention ? <AttentionDot level={item.attention} /> : null}
            </Flex>
          )}
        </NavLink>
      ))}
    </Flex>
  );
}

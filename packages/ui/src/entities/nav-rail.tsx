/** @jsxImportSource @emotion/react */
import { Flex } from 'e-prim';
import { NavLink } from 'react-router';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { usePageInset } from '../layout/page.tsx';
import { NAV_RAIL } from '../theme/grid.ts';
import { AttentionDot } from './attention-dot.tsx';
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
    <Flex
      as="nav"
      aria-label="Pages"
      direction="column"
      align="center"
      gap={2.5}
      background="rail"
      width={NAV_RAIL}
      py={6}
      position="fixed"
      zIndex="nav"
      css={{ top: 0, bottom: 0, left: 0 }}
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

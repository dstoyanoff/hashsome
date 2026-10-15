/** @jsxImportSource @emotion/react */
import { Flex } from 'e-prim';
import { NavLink } from 'react-router';
import { Icon } from '../icon.tsx';
import { usePageInset } from '../layout/page.tsx';
import { AttentionDot } from './attention-dot.tsx';
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
 * surrounding `Page`, which pads for it. */
export function NavDock({ items, base, idleReturn }: NavDockProps) {
  const appDefault = useIdleReturnDefault();
  useIdleReturn({ to: mainPath(items, base), after: idleReturn ?? appDefault });
  usePageInset('bottom', 88);

  return (
    <Flex
      as="nav"
      aria-label="Pages"
      align="center"
      gap={1.5}
      background="rail"
      radius="full"
      p={1.5}
      shadow="dock"
      position="fixed"
      css={({ spacing }) => ({
        bottom: spacing(5),
        left: '50%',
        transform: 'translateX(-50%)',
      })}
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
              radius="full"
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

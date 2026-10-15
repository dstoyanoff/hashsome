/** @jsxImportSource @emotion/react */
import { useTheme } from '@emotion/react';
import { Flex } from 'e-prim';
import { useDebug } from '../debug.ts';
import { centeringOffsets, gridMetrics } from '../theme/grid.ts';
import { GridOverlay } from './grid-overlay.tsx';
import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

type Side = 'left' | 'bottom';

const InsetContext = createContext<((side: Side, size: number) => () => void) | null>(null);

/** Reserves `size` px of the surrounding `Page`'s edge for something fixed over it (`NavRail`,
 * `NavDock`, `NavBar`), so content never sits under it. A no-op outside a `Page`. */
export function usePageInset(side: Side, size: number): void {
  const reserve = useContext(InsetContext);
  useLayoutEffect(() => reserve?.(side, size), [reserve, side, size]);
}

export interface PageProps {
  /** The dashboard. */
  children: ReactNode;

  /** Page height. Default `100dvh`: the whole viewport. Only embedding it somewhere smaller (a preview) needs another. */
  height?: string | number;
}

/** The page every dashboard renders into: padded by the density's spacing, a column with gaps,
 * exactly viewport height, and scrolling inside itself (the document never scrolls — the entity
 * drawer is `position: fixed` and relies on that). It pads further for a `NavRail`, `NavDock` or `NavBar`
 * the dashboard includes. Render it once, in the app's root layout.
 *
 * The debug menu (`HASHSOME_DEBUG=1`) can draw the module grid over the page, spanning all of it (its
 * padding is the grid's first three modules), with every card boxed green or red by whether it sits on
 * the grid: for laying a dashboard out for one device. The height is rarely a whole number of grid
 * modules; what is left over (under one module) is shared between the top and the bottom padding, so the
 * content sits centered and the grid stays whole. */
export function Page({ children, height = '100dvh' }: PageProps) {
  const [insets, setInsets] = useState<Record<Side, number>>({ left: 0, bottom: 0 });
  const reserve = useCallback((side: Side, size: number) => {
    setInsets((current) => ({ ...current, [side]: size }));
    return () => setInsets((current) => ({ ...current, [side]: 0 }));
  }, []);

  const value = useMemo(() => reserve, [reserve]);
  // The debug menu's "Show grid" draws the module grid over the page, for laying a dashboard out.
  const showGrid = useDebug().grid;

  // What is left of the height after whole modules goes half to the top padding and half to the
  // bottom one (in whole pixels, so edges stay crisp).
  const theme = useTheme();
  const space = theme.density?.space;
  const main = useRef<HTMLElement>(null);
  const [centered, setCentered] = useState({ top: 0, bottom: 0 });
  useLayoutEffect(() => {
    const element = main.current;
    if (!element || space === undefined) {
      return;
    }

    const measure = () => {
      const next = centeringOffsets(
        element.clientHeight - 2 * space - insets.bottom,
        gridMetrics(space).module,
      );

      setCentered((current) =>
        current.top === next.top && current.bottom === next.bottom ? current : next,
      );
    };

    measure();
    window.addEventListener('resize', measure);
    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    resize?.observe(element);
    return () => {
      window.removeEventListener('resize', measure);
      resize?.disconnect();
    };
  }, [space, insets.bottom]);

  return (
    <InsetContext.Provider value={value}>
      <Flex
        as="main"
        ref={main}
        data-centered={`${centered.top} ${centered.bottom}`}
        direction="column"
        height={height}
        overflow="auto"
        position="relative"
        gap={3}
        p={3}
        css={({ spacing }) => ({
          // The padding of one space, plus room for a nav that is fixed over the page.
          paddingLeft: `calc(${spacing(3)} + ${insets.left}px)`,
          paddingTop: `calc(${spacing(3)} + ${centered.top}px)`,
          paddingBottom: `calc(${spacing(3)} + ${insets.bottom + centered.bottom}px)`,
          // And the phone's home indicator, which a fixed bar at the bottom reaches under (a wall display has none).
          '@supports (padding-bottom: env(safe-area-inset-bottom))': {
            paddingBottom: `calc(${spacing(3)} + ${insets.bottom + centered.bottom}px + env(safe-area-inset-bottom))`,
          },
          boxSizing: 'border-box',
        })}
      >
        {children}
        {showGrid ? <GridOverlay /> : null}
      </Flex>
    </InsetContext.Provider>
  );
}

import { Maximize2, Minimize2, X } from 'lucide-react';
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { usePresence } from '../motion/presence.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from './detail-provider.tsx';
import { useSheetPull } from './use-sheet-pull.ts';
import styles from './entity-drawer.module.css';

/** How long the drawer takes to leave, in ms, in full motion. */
const EXIT_MS = 260;

const COLLAPSED_WIDTH = 380;
const EDGE_INSET = 20;

/** Narrower than this (a phone), the drawer is a bottom sheet instead of a panel at the side. */
const SHEET_BELOW = 500;

/** How much of the screen's height a sheet may take; one opened tall (`expanded`) takes all of it. */
const SHEET_HEIGHT = '92dvh';

/**
 * The single shared detail drawer, rendered once by `HashsomeProvider`. Floats `EDGE_INSET`px in
 * from the viewport edges (not the triggering tile — it's opened via `Tile`'s `detail` prop and
 * has to fit content far larger than a tile), and can expand to near-fullscreen.
 *
 * The drawer itself only ever sets `width` explicitly (animated) and otherwise stretches to fill
 * its parent — a `position: fixed; inset: 0` wrapper with `padding: EDGE_INSET` and a flex layout
 * that right-aligns it. Deliberately NOT `top`/`right`/`bottom` (or a computed `height`) on the
 * drawer itself: browsers are unreliable at deriving a fixed element's height from opposing
 * insets alone (it can lock to the full viewport height and overflow past the bottom edge,
 * especially on kiosk/tablet WebViews) — `inset: 0` on the wrapper has no such ambiguity (all
 * four sides pinned to 0), and flexbox stretch-sizing the child is a much better-supported path
 * to "fill the available height" than any `top`/`bottom`/`height` arithmetic.
 */
export function EntityDrawer() {
  const { detail: current, closeDetail, toggleExpanded } = useDetail();
  // What was open stays drawn while it leaves (see `usePresence`), so it is kept for that.
  const [last, setLast] = useState(current);
  if (current && current !== last) {
    setLast(current);
  }

  const { mounted, leaving } = usePresence(current !== null, EXIT_MS);
  const detail = current ?? (mounted ? last : null);
  const sheetNode = useRef<HTMLDivElement>(null);
  const pull = useSheetPull(sheetNode, closeDetail);
  const [viewportWidth, setViewportWidth] = useState(() =>
    typeof window === 'undefined' ? 0 : window.innerWidth,
  );

  useEffect(() => {
    if (!detail) {
      return;
    }

    const onResize = () => setViewportWidth(window.innerWidth);
    onResize();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [detail]);

  useEffect(() => {
    if (!detail) {
      return;
    }

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeDetail();
      }
    };

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [detail, closeDetail]);

  const expanded = detail?.expanded ?? false;
  const sheet = viewportWidth > 0 && viewportWidth < SHEET_BELOW;
  const width = expanded
    ? Math.max(0, viewportWidth - EDGE_INSET * 2)
    : Math.min(COLLAPSED_WIDTH, Math.max(0, viewportWidth - EDGE_INSET * 2));

  if (!detail) {
    return null;
  }

  return (
    <>
      <div className={styles.scrim} data-leaving={leaving} onClick={closeDetail} />
      {sheet ? (
        <div className={styles.sheetHost}>
          <div
            ref={sheetNode}
            className={styles.sheet}
            data-tall={expanded || detail.locked === true}
            data-leaving={leaving}
            style={{ '--sheet-height': SHEET_HEIGHT } as CSSProperties}
            role="dialog"
            aria-modal="true"
          >
            <div className={styles.handle} {...pull}>
              <div className={styles.grip}>
                <div className={styles.gripBar} aria-hidden="true" />
              </div>
              <div className={styles.sheetTitle}>
                {detail.header}
                <DrawerActionButton icon={<X />} label="Close" onClick={closeDetail} />
              </div>
            </div>
            <div className={styles.sheetBody}>{detail.body}</div>
          </div>
        </div>
      ) : (
        <div
          className={styles.panelHost}
          style={{ '--edge-inset': `${EDGE_INSET}px` } as CSSProperties}
        >
          <div
            className={styles.panel}
            data-leaving={leaving}
            style={{ width }}
            role="dialog"
            aria-modal="true"
          >
            <div className={styles.panelTitle}>
              {detail.header}
              <div className={styles.actions}>
                {detail.locked ? null : (
                  <DrawerActionButton
                    icon={expanded ? <Minimize2 /> : <Maximize2 />}
                    label={expanded ? 'Collapse' : 'Expand'}
                    onClick={toggleExpanded}
                  />
                )}
                <DrawerActionButton icon={<X />} label="Close" onClick={closeDetail} />
              </div>
            </div>
            {detail.body}
          </div>
        </div>
      )}
    </>
  );
}

function DrawerActionButton({
  icon,
  label,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={styles.action}
      aria-label={label}
      title={label}
      onClick={onClick}
    >
      <Icon size={14}>{icon}</Icon>
    </button>
  );
}

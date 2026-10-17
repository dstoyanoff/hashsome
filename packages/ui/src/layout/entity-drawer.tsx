import { AnimatePresence, motion, useDragControls, type MotionStyle } from 'motion/react';
import { useEffect, useState, type CSSProperties } from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from './detail-provider.tsx';
import styles from './entity-drawer.module.css';

const COLLAPSED_WIDTH = 380;
const EDGE_INSET = 20;

/** Narrower than this (a phone), the drawer is a bottom sheet instead of a panel at the side. */
const SHEET_BELOW = 500;

/** How much of the screen's height a sheet may take; one opened tall (`expanded`) takes all of it. */
const SHEET_HEIGHT = '92dvh';

/** How far a sheet has to be pulled down, or how fast, to be let go. */
const DISMISS_DISTANCE = 100;
const DISMISS_VELOCITY = 500;

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
  const { detail, closeDetail, toggleExpanded } = useDetail();
  const drag = useDragControls();
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

  return (
    <AnimatePresence>
      {detail ? (
        <>
          <motion.div
            key="hash-drawer-scrim"
            className={styles.scrim}
            onClick={closeDetail}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
          />
          {sheet ? (
            <div className={styles.sheetHost}>
              <motion.div
                key="hash-drawer"
                className={styles.sheet}
                data-tall={expanded || detail.locked === true}
                style={{ '--sheet-height': SHEET_HEIGHT } as MotionStyle}
                role="dialog"
                aria-modal="true"
                initial={{ y: '100%' }}
                animate={{ y: 0 }}
                exit={{ y: '100%' }}
                transition={{ type: 'spring', damping: 34, stiffness: 340, mass: 0.9 }}
                drag="y"
                dragControls={drag}
                dragListener={false}
                dragConstraints={{ top: 0, bottom: 0 }}
                dragElastic={{ top: 0, bottom: 0.5 }}
                onDragEnd={(_event, info) => {
                  if (info.offset.y > DISMISS_DISTANCE || info.velocity.y > DISMISS_VELOCITY) {
                    closeDetail();
                  }
                }}
              >
                <div className={styles.handle} onPointerDown={(event) => drag.start(event)}>
                  <div className={styles.grip}>
                    <div className={styles.gripBar} aria-hidden="true" />
                  </div>
                  <div className={styles.sheetTitle}>
                    {detail.header}
                    <DrawerActionButton icon="lu:x" label="Close" onClick={closeDetail} />
                  </div>
                </div>
                <div className={styles.sheetBody}>{detail.body}</div>
              </motion.div>
            </div>
          ) : (
            <div
              className={styles.panelHost}
              style={{ '--edge-inset': `${EDGE_INSET}px` } as CSSProperties}
            >
              <motion.div
                key="hash-drawer"
                className={styles.panel}
                role="dialog"
                aria-modal="true"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1, width }}
                exit={{ opacity: 0 }}
                transition={{
                  width: { duration: 0.32, ease: [0.2, 0.8, 0.2, 1] },
                  opacity: { duration: 0.28 },
                }}
              >
                <div className={styles.panelTitle}>
                  {detail.header}
                  <div className={styles.actions}>
                    {detail.locked ? null : (
                      <DrawerActionButton
                        icon={expanded ? 'lu:minimize-2' : 'lu:maximize-2'}
                        label={expanded ? 'Collapse' : 'Expand'}
                        onClick={toggleExpanded}
                      />
                    )}
                    <DrawerActionButton icon="lu:x" label="Close" onClick={closeDetail} />
                  </div>
                </div>
                {detail.body}
              </motion.div>
            </div>
          )}
        </>
      ) : null}
    </AnimatePresence>
  );
}

function DrawerActionButton({
  icon,
  label,
  onClick,
}: {
  icon: IconName;
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
      <Icon name={icon} size={14} />
    </button>
  );
}

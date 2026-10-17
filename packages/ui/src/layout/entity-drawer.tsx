/** @jsxImportSource @emotion/react */
import { Flex, type PaletteKey } from '../style/prim.tsx';
import { AnimatePresence, motion, useDragControls } from 'motion/react';
import { useEffect, useState } from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from './detail-provider.tsx';

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
          <Flex
            as={motion.div}
            key="hash-drawer-scrim"
            onClick={closeDetail}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.2 }}
            zIndex="scrim"
            css={{ position: 'fixed', inset: 0, background: 'rgb(0 0 0 / 0.45)' }}
          />
          {sheet ? (
            <Flex
              direction="column"
              justify="flex-end"
              zIndex="scrim"
              css={{ position: 'fixed', inset: 0, pointerEvents: 'none' }}
            >
              <Flex
                as={motion.div}
                key="hash-drawer"
                role="dialog"
                aria-modal="true"
                direction="column"
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
                background="surface"
                color="text"
                shadow="drawer"
                width="100%"
                minHeight={0}
                css={({ palette }) => ({
                  boxSizing: 'border-box',
                  borderRadius: '24px 24px 0 0',
                  borderTop: `1px solid ${palette.border}`,
                  maxHeight: SHEET_HEIGHT,
                  ...(expanded || detail.locked ? { height: SHEET_HEIGHT } : {}),
                  pointerEvents: 'auto',
                  overflow: 'hidden',
                })}
              >
                {/* What is pulled to let it go: the grip and the title; the content under it scrolls on its own. */}
                <Flex
                  direction="column"
                  onPointerDown={(event) => drag.start(event)}
                  css={{ flex: 'none', touchAction: 'none', cursor: 'grab' }}
                >
                  <Flex justify="center" css={{ paddingTop: 8, paddingBottom: 4 }}>
                    <Flex
                      aria-hidden="true"
                      background="line"
                      radius="full"
                      css={{ width: 36, height: 5, opacity: 0.6 }}
                    />
                  </Flex>
                  <Flex justify="space-between" align="center" gap={2.5} px={5} pt={2} pb={3}>
                    {detail.header}
                    <DrawerActionButton icon="lu:x" label="Close" onClick={closeDetail} />
                  </Flex>
                </Flex>
                <Flex
                  direction="column"
                  grow={1}
                  minHeight={0}
                  px={5}
                  css={{
                    overflowY: 'auto',
                    overscrollBehavior: 'contain',
                    // Clear of the phone's home indicator.
                    paddingBottom: 'calc(20px + env(safe-area-inset-bottom, 0px))',
                  }}
                >
                  {detail.body}
                </Flex>
              </Flex>
            </Flex>
          ) : (
            <Flex
              justify="flex-end"
              zIndex="scrim"
              css={{
                position: 'fixed',
                inset: 0,
                // `content-box` (the default) is the actual bug: with `inset: 0` and no explicit
                // height, the browser resolves the auto height from the insets alone, THEN adds
                // padding on top of that — overflowing past the viewport by exactly 2x the padding.
                // `border-box` makes padding come out of that resolved height instead of adding to it.
                boxSizing: 'border-box',
                padding: EDGE_INSET,
                pointerEvents: 'none',
              }}
            >
              <Flex
                as={motion.div}
                direction="column"
                key="hash-drawer"
                role="dialog"
                aria-modal="true"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1, width }}
                exit={{ opacity: 0 }}
                transition={{
                  width: { duration: 0.32, ease: [0.2, 0.8, 0.2, 1] },
                  opacity: { duration: 0.28 },
                }}
                background="surface"
                color="text"
                radius="drawer"
                shadow="drawer"
                border
                zIndex="drawer"
                // Explicit, not implicit flex stretch: a flex item that's also a scroll container
                // (`overflow: auto`) doesn't reliably stay within its parent's cross-axis size —
                // its default `min-height: auto` can win out and push it past the stretched height
                // instead of scrolling internally. `height: '100%'` here is well-defined regardless
                // (the wrapper's `inset: 0` gives it an unambiguous content-box size).
                height="100%"
                minHeight={0}
                maxHeight="100%"
                overflow="auto"
                p={5}
                css={{ pointerEvents: 'auto' }}
              >
                <Flex justify="space-between" align="center" gap={2.5} mb={4}>
                  {detail.header}
                  <Flex gap={1.5} grow={0}>
                    {detail.locked ? null : (
                      <DrawerActionButton
                        icon={expanded ? 'lu:minimize-2' : 'lu:maximize-2'}
                        label={expanded ? 'Collapse' : 'Expand'}
                        onClick={toggleExpanded}
                      />
                    )}
                    <DrawerActionButton icon="lu:x" label="Close" onClick={closeDetail} />
                  </Flex>
                </Flex>
                {detail.body}
              </Flex>
            </Flex>
          )}
        </>
      ) : null}
    </AnimatePresence>
  );
}

const ACTION_BACKGROUND: PaletteKey = 'surfaceRaised';
const ACTION_COLOR: PaletteKey = 'text';

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
    <Flex
      as="button"
      type="button"
      aria-label={label}
      title={label}
      onClick={onClick}
      background={ACTION_BACKGROUND}
      color={ACTION_COLOR}
      radius="small"
      align="center"
      justify="center"
      cursor="pointer"
      width={30}
      height={30}
    >
      <Icon name={icon} size={14} />
    </Flex>
  );
}

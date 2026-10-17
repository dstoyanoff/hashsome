/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import { useColorByKey } from 'e-prim';
import { Box, Flex, Typography } from '../style/prim.tsx';
import { AnimatePresence, motion } from 'motion/react';
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { RoundButton } from './round-button.tsx';
import { useStill } from './use-still.ts';
import { SpinnerRing } from './spinner.tsx';
import { statusLabels, type EntityStatus } from '../status.ts';
import { BADGE } from '../theme/grid.ts';
import { useDrawer } from './use-drawer.tsx';
import { EnergyChart, type EnergyChartProps } from './energy-chart.tsx';
import { HistorySection, type LogbookEntry } from './history-section.tsx';
import { HoldProgress } from './hold-progress.tsx';
import { LogbookHistory } from './logbook-history.tsx';

export interface TileProps {
  /** The tile's name. */
  label: string;

  /** Icon id, e.g. `lu:lightbulb`. */
  icon?: IconName;

  /** A literal CSS color (e.g. `#8B5CF6`) that fills the icon's badge, with a light glyph, so tiles of one kind (scenes, say) can be told apart at a glance. The palette has too few colors for that, so each app picks its own. Only while the tile is ready; otherwise the badge is the usual one. */
  iconColor?: string;

  /** Second line. Replaced by the status label when the entity is not ready. */
  secondary?: ReactNode;

  /** Status of what the tile shows; anything but `ready` disables it and shows why (Loading, Unavailable, Not found). */
  status?: EntityStatus;

  /** Whether the thing it controls is on: lights the icon (and the whole card for an on/off tile) with the accent color. */
  active?: boolean;

  /** Highlights the tile as pending / done / failed after an action. */
  feedback?: 'pending' | 'done' | 'error' | undefined;

  /** Called on a tap — not after a drag or a hold. */
  onPress?: (() => void) | undefined;

  /** 0..1. Renders a fill bar; enables drag and arrow keys to change it. */
  fill?: number;

  /** Called once when a drag ends or an arrow key is pressed. */
  onFillChange?: (fill: number) => void;

  /** Controls shown on the right (steppers, icon buttons). */
  trailing?: ReactNode;

  /** Replaces the label and status with custom content (e.g. color swatches) while set; the tile stops being one big button. `openDetail` opens the drawer. */
  overlay?: (controls: { openDetail: () => void }) => ReactNode;

  /** Native tooltip text. */
  title?: string;

  /** Rendered in the shared detail drawer on a long-press (~500ms). Adds the hold gesture. */
  detail?: ReactNode;

  /** Subtitle under the drawer header's name, e.g. "Light". Only used when `detail` is set. */
  kind?: string;

  /**
   * A power-draw chart, shown in the drawer above `history` (e.g. from a Shelly's energy
   * monitoring). Generic across every entity type.
   */
  energy?: EnergyChartProps;

  /**
   * Recent activity, shown in the drawer below `detail`/`energy`. Generic across every entity
   * type — whichever component has a way to fetch it just passes it through.
   */
  history?: LogbookEntry[];

  /**
   * An entity whose recent activity to fetch from the backend when the person opens the drawer's
   * History section, which is folded by default (Home Assistant's logbook). `history`, when given, is
   * used instead. A backend that keeps none says so there.
   */
  logbook?: EntityRef;
}

const DRAG_THRESHOLD = 8;
const KEY_STEP = 0.05;
const HOLD_MS = 500;
const COLOR_TRANSITION = { duration: 0.28 };
const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

const valueAt = (event: PointerEvent<HTMLButtonElement>) => {
  const rect = event.currentTarget.getBoundingClientRect();
  return rect.width > 0 ? clamp01((event.clientX - rect.left) / rect.width) : 0;
};

/** Base pill used by all entity tiles. Tap to press; hold (with `detail` set) to open the drawer. */
export function Tile({
  label,
  icon,
  iconColor: tint,
  secondary,
  status = 'ready',
  active = false,
  feedback,
  onPress,
  fill,
  onFillChange,
  trailing,
  overlay,
  title,
  detail,
  kind,
  energy,
  history,
  logbook,
}: TileProps) {
  const still = useStill();
  const ready = status === 'ready';
  const adjustable = fill !== undefined && onFillChange !== undefined && ready;
  const holdable =
    (detail !== undefined ||
      energy !== undefined ||
      history !== undefined ||
      logbook !== undefined) &&
    ready;

  const drawerBody = useMemo(
    () => (
      <>
        {detail}
        {energy ? <EnergyChart {...energy} /> : null}
        {history ? (
          <HistorySection entries={history} />
        ) : logbook ? (
          <LogbookHistory entity={logbook} />
        ) : null}
      </>
    ),
    [detail, energy, history, logbook],
  );

  const [drag, setDrag] = useState<number | null>(null);
  const [holding, setHolding] = useState(false);
  const start = useRef<{ x: number; dragging: boolean } | null>(null);
  const justDragged = useRef(false);
  const holdFired = useRef(false);
  const holdTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearHoldTimer = () => {
    if (holdTimer.current !== null) {
      clearTimeout(holdTimer.current);
      holdTimer.current = null;
    }

    setHolding(false);
  };

  // `clearHoldTimer` only touches refs and a state setter, so it is safe to run once on unmount.
  // oxlint-disable-next-line react/exhaustive-effect-dependencies
  useEffect(() => clearHoldTimer, []);

  // `onFillChange` only requests the new value — the entity's actual `fill` doesn't update until
  // the backend confirms it and a new prop flows back down, which is never in the same tick. If a
  // drag/key-nudge cleared `drag` immediately, `shownFill` would fall through to the still-stale
  // `fill` prop and visibly snap back to the pre-drag position until the round trip completes.
  // Keeping `drag` set to the just-committed value instead, and only clearing it once `fill`
  // actually catches up to match, makes the handoff from optimistic to real state seamless.
  // Adjusting state during render (React's documented pattern for this, not an effect) so the
  // clear-out and the `fill` update that triggered it land in the same commit, with no extra
  // stale-`drag` frame in between.
  if (drag !== null && fill !== undefined && Math.abs(fill - drag) < 0.005) {
    setDrag(null);
  }

  const { open: openDrawer } = useDrawer({ icon, label, kind, body: drawerBody });

  const onPointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    if (adjustable) {
      start.current = { x: event.clientX, dragging: false };
      event.currentTarget.setPointerCapture?.(event.pointerId);
    }

    if (holdable) {
      holdFired.current = false;
      setHolding(true);
      holdTimer.current = setTimeout(() => {
        holdFired.current = true;
        clearHoldTimer();
        start.current = null;
        openDrawer();
      }, HOLD_MS);
    }
  };

  const onPointerMove = (event: PointerEvent<HTMLButtonElement>) => {
    const s = start.current;
    if (!s) {
      return;
    }

    if (!s.dragging && Math.abs(event.clientX - s.x) > DRAG_THRESHOLD) {
      s.dragging = true;
      clearHoldTimer();
    }

    if (s.dragging) {
      setDrag(valueAt(event));
    }
  };

  const onPointerUp = (event: PointerEvent<HTMLButtonElement>) => {
    clearHoldTimer();
    const s = start.current;
    start.current = null;
    if (!s?.dragging) {
      return;
    }

    justDragged.current = true;
    const next = valueAt(event);
    setDrag(next);
    onFillChange?.(next);
  };

  const onPointerCancel = () => {
    clearHoldTimer();
    start.current = null;
    setDrag(null);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!adjustable) {
      return;
    }

    if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
      event.preventDefault();
      const delta = event.key === 'ArrowRight' ? KEY_STEP : -KEY_STEP;
      const next = clamp01(Math.round(((drag ?? fill ?? 0) + delta) * 100) / 100);
      setDrag(next);
      onFillChange?.(next);
    }
  };

  const onClick = () => {
    if (justDragged.current) {
      justDragged.current = false;
      return;
    }

    if (holdFired.current) {
      holdFired.current = false;
      return;
    }

    onPress?.();
  };

  const shownFill = drag ?? fill;
  // `active` reflects the entity's current (pre-drag) on/off state — gating the fill bar on it
  // alone hides it entirely while dragging an off light up to a brightness, since it doesn't turn
  // on until the backend confirms. `drag !== null` keeps it visible for the live preview regardless.
  const overlaid = overlay !== undefined;
  const hasFill = shownFill !== undefined && ready && (active || drag !== null);
  // Stays mounted (just transparent) while an overlay is open: unmounting it would replay the
  // 0 → value grow animation on close, as if the light had just been turned on.
  const showFill = hasFill && !overlaid;
  // While actively dragging, show the live percentage instead of the (now stale) `secondary` prop
  // — the entity's own state, and thus `secondary`, only catches up once `onFillChange` commits.
  const liveSecondary = drag !== null ? `${Math.round(drag * 100)}%` : secondary;

  const surface = useColorByKey('surface') ?? '';
  const surfaceRaised = useColorByKey('surfaceRaised') ?? '';
  const accent = useColorByKey('accent') ?? '';
  const text = useColorByKey('text') ?? '';
  const textMuted = useColorByKey('textMuted') ?? '';
  const accentText = useColorByKey('accentText') ?? '';
  const line = useColorByKey('line') ?? '';

  const accented = active && ready;
  const isDimmable = fill !== undefined;
  // A dimmable tile never gets a solid accent-colored CARD — its own `fill` bar is the "how on"
  // indicator instead, growing from neutral `surface`/`text` — unlike a solid on/off tile, whose
  // whole card turns `accent`. `solidAccent` still gates `cardBg` (and the hold-progress wash
  // below, which genuinely needs to pick a dark-on-orange vs light-on-neutral highlight) for that
  // reason, but text/icon colors below do NOT follow it — seeing both a solid-accent tile and a
  // dimmable one at 100% fill side by side (same visible solid-orange card either way) with two
  // different text colors reads as a bug even though each one is "correct" for its own case.
  // `text`/`line` read fine against `accent` in both themes (dark-on-orange and near-white-on-orange
  // are each decent contrast) as well as against `surface`, where `accentText` (white) is only
  // readable in dark mode and invisible in light — so every accented state just uses the normal
  // ready-state color, never a separate white-on-accent one.
  const solidAccent = accented && !isDimmable;
  let cardBg = surface;
  // The label keeps the neutral light `text` everywhere; only the status line (e.g. "100%"), which
  // sits at the far right, goes near-black (`onAccent`) once the card behind it is orange — the
  // muted `line` grey it normally uses is ~1.6:1 on orange. `fill`'s opacity scales with
  // brightness, so it only flips once the fill is nearly solid (or the whole card is accent).
  const wholeCardAccent = (solidAccent && !overlaid) || feedback === 'done';
  const fillLevel = showFill ? (shownFill ?? 0) : 0;
  const statusOnAccent = ready && (wholeCardAccent || fillLevel > 0.85);
  const cardColor = ready ? text : textMuted;
  if ((solidAccent && !overlaid) || feedback === 'done') {
    cardBg = accent;
  }

  // An app's own color for the icon (a scene's) fills the badge while the tile is ready, with the light glyph.
  const tinted = tint !== undefined && ready;
  const iconBg = tinted ? tint : accented && !overlaid ? 'rgba(0, 0, 0, 0.25)' : surfaceRaised;
  // The icon glyph sits inside that translucent badge, which self-adapts to whatever's behind it
  // (the orange fill at a high `fill`, plain `surface` at a low one) — so it still needs the
  // lighter `accentText` glyph once accented, unlike the flat label/secondary text above.
  const iconColor = tinted || (accented && !overlaid) ? accentText : ready ? line : textMuted;
  // The label always stays full brightness; only the icon glyph and this line dim when the tile is
  // off (matches the converged mockup — `#726C6A`/`line`, not `textMuted`, which is reserved for
  // the unavailable/unknown/missing/loading statuses below).
  const dimColorKey: 'line' | 'textMuted' = ready ? 'line' : 'textMuted';
  const cardOpacity = !ready || feedback === 'pending' ? 0.7 : 1;

  const iconBadge = icon ? (
    <Flex
      as={still ? 'span' : motion.span}
      align="center"
      justify="center"
      position="relative"
      radius="full"
      width={32}
      height={32}
      {...(still
        ? { style: { backgroundColor: iconBg, color: iconColor } }
        : {
            initial: false,
            animate: { backgroundColor: iconBg, color: iconColor },
            transition: COLOR_TRANSITION,
          })}
      css={{ flex: 'none' }}
    >
      <Icon name={icon} size={16} />
    </Flex>
  ) : null;

  return (
    <Flex
      as={still ? 'div' : motion.div}
      radius="card"
      position="relative"
      align="center"
      overflow="hidden"
      minWidth={0}
      data-grid-card
      data-status={status}
      data-active={accented}
      data-solid-accent={solidAccent}
      data-fill={fill !== undefined}
      data-fill-visible={showFill}
      data-overlay={overlay !== undefined}
      data-pending={feedback === 'pending'}
      data-feedback={feedback === 'pending' ? undefined : feedback}
      // A card that mounts (a page opened) is drawn as it is, not animated from nothing. Where nothing moves it is
      // just drawn: a color that is animated is a motion element on every tile, and the main thread repainting it.
      {...(still
        ? { style: { backgroundColor: cardBg, color: cardColor, opacity: cardOpacity } }
        : {
            initial: false,
            animate: { backgroundColor: cardBg, color: cardColor, opacity: cardOpacity },
            transition: COLOR_TRANSITION,
          })}
      css={({ palette, spacing }) => ({
        // The badge between its padding, written down rather than left to the content, so a tile is a
        // whole number of grid modules (12, in the comfortable density) whatever is in it.
        minHeight: `calc(${spacing(4)} + ${BADGE}px)`,
        ...(feedback === 'error'
          ? { outline: `2px solid ${palette.danger}`, outlineOffset: -2 }
          : {}),
      })}
    >
      {hasFill ? (
        <Box
          as={still ? 'span' : motion.span}
          position="absolute"
          background="accent"
          // Spans the whole card, including behind `trailing` (e.g. a color-capable light's
          // picker button) — it's a sibling of the button and `trailing`, not nested inside the
          // button, specifically so its width isn't capped at the button's own narrower bounds.
          {...(still
            ? { style: { width: `${(shownFill ?? 0) * 100}%`, opacity: overlaid ? 0 : shownFill } }
            : {
                initial: false,
                animate: { width: `${(shownFill ?? 0) * 100}%`, opacity: overlaid ? 0 : shownFill },
                transition: drag !== null ? { duration: 0 } : COLOR_TRANSITION,
              })}
          css={{ inset: '0 auto 0 0', pointerEvents: 'none' }}
        />
      ) : null}
      {holdable && holding ? (
        // Visible against either background: a light wash normally, a dark wash on an
        // accent-colored (non-dimmable-active) card, where a light or accent bar would
        // disappear or clash.
        <HoldProgress
          edge="bottom"
          color={solidAccent ? 'rgba(0, 0, 0, 0.35)' : 'rgba(255, 255, 255, 0.55)'}
        />
      ) : null}
      {overlay ? (
        <Flex
          align="center"
          gap={2.5}
          minWidth={0}
          grow={1}
          position="relative"
          pt={2}
          pr={2}
          pb={2}
          pl={2}
          css={{ alignSelf: 'stretch' }}
        >
          {iconBadge}
          <Flex grow={1} minWidth={0} justify="center" align="center">
            {overlay({ openDetail: openDrawer })}
          </Flex>
        </Flex>
      ) : (
        <Flex
          as="button"
          type="button"
          disabled={!ready || (!onPress && !adjustable && !holdable)}
          onClick={onClick}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerCancel}
          onKeyDown={onKeyDown}
          title={title}
          aria-label={label}
          align="center"
          gap={2.5}
          minWidth={0}
          grow={1}
          position="relative"
          cursor={ready ? 'pointer' : 'default'}
          pt={2}
          pr={3.5}
          pb={2}
          pl={2}
          css={{
            alignSelf: 'stretch',
            background: 'none',
            textAlign: 'left',
            touchAction: 'pan-y',
            WebkitTapHighlightColor: 'transparent',
            '&:focus-visible': {
              outline: `2px solid ${accent}`,
              outlineOffset: -2,
              borderRadius: 'inherit',
            },
          }}
        >
          {iconBadge}
          <Typography
            variant="label"
            as="span"
            noWrap
            textOverflow="ellipsis"
            position="relative"
            grow={1}
            minWidth={0}
          >
            {label}
          </Typography>
          {!ready ? (
            <Typography
              variant="secondary"
              as="span"
              noWrap
              textOverflow="ellipsis"
              color="textMuted"
              position="relative"
              css={{ flex: 'none' }}
            >
              {statusLabels[status]}
            </Typography>
          ) : liveSecondary ? (
            <Typography
              variant="secondary"
              as="span"
              noWrap
              textOverflow="ellipsis"
              color={statusOnAccent ? 'onAccent' : dimColorKey}
              position="relative"
              css={{ flex: 'none' }}
            >
              {liveSecondary}
            </Typography>
          ) : null}
        </Flex>
      )}
      {trailing ? (
        // `position: relative` (any value but `static`) is required here, not optional: an
        // absolutely-positioned element always paints above a `position: static` one in the same
        // stacking context regardless of DOM order, so once the fill bar above started reaching
        // into this area, it painted over `trailing` despite coming first in the markup.
        <Flex align="center" gap={2} pr={2.5} position="relative">
          {trailing}
        </Flex>
      ) : null}
    </Flex>
  );
}

export function IconButton({
  icon,
  label,
  onClick,
  disabled,
  active,
  pressed,
  primary,
  size,
  glyph,
  color,
  feedback,
}: {
  icon: IconName;
  label: string;
  onClick?: () => void;
  disabled?: boolean;
  active?: boolean;
  primary?: boolean;

  /** Icon glyph size in px; the button's own circle scales with it (2x). Defaults to this button's usual, larger size when omitted. */
  size?: number;

  /** Icon glyph size in px, leaving the button's circle at its usual size (unlike `size`, which scales both). */
  glyph?: number;

  /** Literal CSS color for the glyph (e.g. a light's current color). */
  color?: string;

  /** For a toggle: whether it is on, announced to assistive tech (`aria-pressed`). Pair it with `active` to color it. */
  pressed?: boolean;

  /** Shows the outcome of an action the press started: `pending` turns a ring around it and stops it taking another press, `done` flashes it with the accent, `error` turns it red. Every press also gives a small squeeze. */
  feedback?: 'pending' | 'done' | 'error' | undefined;
}) {
  const ring = feedback === 'pending';
  const still = useStill();
  // The icon morphs (turns out, the next turns in) only once it has changed: a list of rows has a few of these in each,
  // and almost none of them ever change, so the animation machinery is not there until it has something to do.
  const [morphs, setMorphs] = useState(false);
  const [drawn, setDrawn] = useState(icon);
  if (icon !== drawn) {
    setDrawn(icon);
    if (!still && !morphs) {
      setMorphs(true);
    }
  }

  const glyphSize = glyph !== undefined ? { size: glyph } : size !== undefined ? { size } : {};
  const content = (
    <>
      {morphs && !still ? (
        <AnimatePresence mode="popLayout">
          <Box
            as={motion.span}
            key={icon}
            css={{ display: 'grid' }}
            initial={{ rotate: -90, opacity: 0, scale: 0.6 }}
            animate={{ rotate: 0, opacity: 1, scale: 1 }}
            exit={{ rotate: 90, opacity: 0, scale: 0.6 }}
            transition={{ duration: 0.18 }}
          >
            <Icon name={icon} {...glyphSize} />
          </Box>
        </AnimatePresence>
      ) : (
        <Box as="span" css={{ display: 'grid' }}>
          <Icon name={icon} {...glyphSize} />
        </Box>
      )}
      {ring ? <SpinnerRing /> : null}
    </>
  );

  const common = {
    // A circle twice the glyph, or the density's usual one.
    ...(size !== undefined ? { size: size * 2 } : {}),
    // A literal color (a light's own) has to outrank the state colors the shared styles give
    // `data-active`, hence the doubled selector for the extra specificity.
    ...(color ? { css: { '&&&': { color } } } : {}),
    'data-feedback': feedback,
    'aria-label': label,
    title: label,
    disabled: disabled || feedback === 'pending',
    'data-active': active,
    'aria-pressed': pressed,
    'data-primary': primary,
    onClick,
  };

  // A plain button where nothing moves; the squeeze on a press is a motion element's.
  return still ? (
    <RoundButton {...common}>{content}</RoundButton>
  ) : (
    <RoundButton
      as={motion.button}
      whileTap={disabled || feedback === 'pending' ? {} : { scale: 0.88 }}
      transition={{ duration: 0.12 }}
      {...common}
    >
      {content}
    </RoundButton>
  );
}

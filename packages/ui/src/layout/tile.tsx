import type { EntityRef } from '@hashsome/core';
import { AnimatePresence, motion, type MotionStyle } from 'motion/react';
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
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
import { useDrawer } from './use-drawer.tsx';
import { EnergyChart, type EnergyChartProps } from './energy-chart.tsx';
import { HistorySection, type LogbookEntry } from './history-section.tsx';
import { HoldProgress } from './hold-progress.tsx';
import { LogbookHistory } from './logbook-history.tsx';
import styles from './tile.module.css';

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

  const accented = active && ready;
  const isDimmable = fill !== undefined;
  // A dimmable tile never gets a solid accent-colored CARD — its own `fill` bar is the "how on" indicator instead, growing from
  // neutral `surface` — unlike a solid on/off tile, whose whole card turns `accent`. Text and icon colors do NOT follow it: seeing a
  // solid-accent tile and a dimmable one at 100% fill side by side (the same solid orange either way) with two different text colors
  // reads as a bug even though each is "correct" for its own case. `text` and `line` read fine against `accent` in both themes, as
  // well as against `surface`, where `accentText` (white) is only readable in dark mode.
  const solidAccent = accented && !isDimmable;
  const wholeCardAccent = (solidAccent && !overlaid) || feedback === 'done';
  const fillLevel = showFill ? (shownFill ?? 0) : 0;
  const statusOnAccent = ready && (wholeCardAccent || fillLevel > 0.85);

  // An app's own color for the icon (a scene's) fills the badge while the tile is ready, with the light glyph.
  const tinted = tint !== undefined && ready;
  const badgeTone = tinted ? 'tint' : accented && !overlaid ? 'accent' : ready ? 'ready' : 'muted';
  const statusTone = !ready ? 'muted' : statusOnAccent ? 'on-accent' : 'dim';

  const iconBadge = icon ? (
    <span
      className={styles.badge}
      data-tone={badgeTone}
      style={tinted ? ({ '--tint': tint } as CSSProperties) : undefined}
    >
      <Icon name={icon} size={16} />
    </span>
  ) : null;

  return (
    <div
      className={styles.card}
      data-grid-card
      data-status={status}
      data-ready={ready}
      data-active={accented}
      data-solid-accent={solidAccent}
      data-whole-accent={wholeCardAccent}
      data-fill={fill !== undefined}
      data-fill-visible={showFill}
      data-overlay={overlay !== undefined}
      data-pending={feedback === 'pending'}
      data-feedback={feedback === 'pending' ? undefined : feedback}
    >
      {hasFill ? (
        <span
          className={styles.fill}
          data-dragging={drag !== null}
          style={{ width: `${(shownFill ?? 0) * 100}%`, opacity: overlaid ? 0 : shownFill }}
        />
      ) : null}
      {holdable && holding ? (
        // Visible against either background: a light wash normally, a dark wash on an accent-colored
        // (non-dimmable-active) card, where a light or accent bar would disappear or clash.
        <HoldProgress
          edge="bottom"
          color={solidAccent ? 'rgba(0, 0, 0, 0.35)' : 'rgba(255, 255, 255, 0.55)'}
        />
      ) : null}
      {overlay ? (
        <div className={styles.overlay}>
          {iconBadge}
          <div className={styles.overlayBody}>{overlay({ openDetail: openDrawer })}</div>
        </div>
      ) : (
        <button
          type="button"
          className={styles.button}
          disabled={!ready || (!onPress && !adjustable && !holdable)}
          onClick={onClick}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerCancel}
          onKeyDown={onKeyDown}
          title={title}
          aria-label={label}
        >
          {iconBadge}
          <span className={styles.label}>{label}</span>
          {!ready ? (
            <span className={styles.status} data-tone="muted">
              {statusLabels[status]}
            </span>
          ) : liveSecondary ? (
            <span className={styles.status} data-tone={statusTone}>
              {liveSecondary}
            </span>
          ) : null}
        </button>
      )}
      {trailing ? <div className={styles.trailing}>{trailing}</div> : null}
    </div>
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
          <motion.span
            key={icon}
            className={styles.glyph}
            initial={{ rotate: -90, opacity: 0, scale: 0.6 }}
            animate={{ rotate: 0, opacity: 1, scale: 1 }}
            exit={{ rotate: 90, opacity: 0, scale: 0.6 }}
            transition={{ duration: 0.18 }}
          >
            <Icon name={icon} {...glyphSize} />
          </motion.span>
        </AnimatePresence>
      ) : (
        <span className={styles.glyph}>
          <Icon name={icon} {...glyphSize} />
        </span>
      )}
      {ring ? <SpinnerRing /> : null}
    </>
  );

  const common = {
    // A circle twice the glyph, or the density's usual one.
    ...(size !== undefined ? { size: size * 2 } : {}),
    // A literal color (a light's own) has to outrank the state colors the shared styles give `data-active`, hence `!important`.
    ...(color ? { className: styles.colored } : {}),
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
    <RoundButton {...common} {...(color ? { style: { '--glyph': color } as CSSProperties } : {})}>
      {content}
    </RoundButton>
  ) : (
    <RoundButton
      as={motion.button}
      whileTap={disabled || feedback === 'pending' ? {} : { scale: 0.88 }}
      transition={{ duration: 0.12 }}
      {...common}
      {...(color ? { style: { '--glyph': color } as MotionStyle } : {})}
    >
      {content}
    </RoundButton>
  );
}

import { Power } from 'lucide-react';
import { MarqueeText } from './marquee-text.tsx';
import {
  useEffect,
  useRef,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import { Icon } from '../icon.tsx';
import { FADE, FadeScroll } from './fade-scroll.tsx';
import { RoundButton } from './round-button.tsx';
import { cx } from '../style/cx.ts';
import styles from './drawer-controls.module.css';
import { useGlide } from './use-glide.ts';
import { useStill } from './use-still.ts';

/** Fine adjustment: a press nudges by `delta` (1%); holding repeats in steps of 10 until released. */
export function StepButton({
  icon,
  label,
  delta,
  holdDelta = delta * 10,
  onStep,
  disabled,
  size = 36,
  plain = false,
}: {
  icon: ReactNode;
  label: string;
  delta: number;

  /** Amount per repeat while held. Default `delta * 10`. */
  holdDelta?: number;
  onStep: (delta: number) => void;
  disabled?: boolean;

  /** Button diameter in px. Default 36. */
  size?: number;

  /** Just the icon, with no circle behind it. Default `false`. */
  plain?: boolean;
}) {
  const timers = useRef<{
    delay?: ReturnType<typeof setTimeout>;
    repeat?: ReturnType<typeof setInterval>;
  }>({});

  const held = useRef(false);
  const stop = () => {
    clearTimeout(timers.current.delay);
    clearInterval(timers.current.repeat);
  };

  // `stop` only touches refs, so it is safe to run once on unmount.
  // oxlint-disable-next-line react/exhaustive-effect-dependencies
  useEffect(() => stop, []);

  return (
    <RoundButton
      size={size}
      className={cx(styles.step, plain && styles.plain)}
      aria-label={label}
      title={label}
      disabled={disabled}
      onPointerDown={() => {
        held.current = false;
        timers.current.delay = setTimeout(() => {
          held.current = true;
          onStep(holdDelta);
          timers.current.repeat = setInterval(() => onStep(holdDelta), 400);
        }, 400);
      }}
      onPointerUp={stop}
      onPointerLeave={stop}
      onPointerCancel={stop}
      onClick={() => {
        if (!held.current) {
          onStep(delta);
        }

        held.current = false;
      }}
    >
      <Icon size={16}>{icon}</Icon>
    </RoundButton>
  );
}

/** A thumbless bar (like the tile's own fill): drag anywhere on it, or use the arrow keys. Plain
 * `fill` shows accent up to the value; a `gradient` track stays vivid up to the value and dims past it instead (no marker or thumb). */
export function ValueBar({
  label,
  value,
  min,
  max,
  keyStep,
  round = 1,
  gradient,
  height = 28,
  track = 'surface',
  onDrag,
  onCommit,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  keyStep: number;
  round?: number;
  gradient?: string;

  /** Bar thickness in px. Default 28. */
  height?: number;

  /** The track's color: `surfaceRaised` where the bar sits straight on the drawer, `surface` (the default) on a raised card. */
  track?: 'surface' | 'surfaceRaised';
  onDrag: (value: number) => void;
  onCommit: (value: number) => void;
}) {
  const dragging = useRef(false);
  const lastDrag = useRef<number | null>(null);
  const fraction = max === min ? 0 : (value - min) / (max - min);
  const pct = Math.min(1, Math.max(0, fraction)) * 100;
  const clampValue = (v: number) => Math.min(max, Math.max(min, v));
  const valueAt = (event: PointerEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    if (rect.width === 0) {
      return value;
    }

    const f = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
    return clampValue(Math.round((min + f * (max - min)) / round) * round);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const dir =
      event.key === 'ArrowRight' || event.key === 'ArrowUp'
        ? 1
        : event.key === 'ArrowLeft' || event.key === 'ArrowDown'
          ? -1
          : 0;

    if (dir !== 0) {
      event.preventDefault();
      onCommit(clampValue(value + dir * keyStep));
    }
  };

  return (
    <div
      className={styles.bar}
      data-track={track}
      data-gradient={gradient !== undefined}
      style={
        {
          '--bar-height': `${height}px`,
          ...(gradient ? { '--gradient': gradient } : {}),
        } as CSSProperties
      }
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      onPointerDown={(event: PointerEvent<HTMLDivElement>) => {
        dragging.current = true;
        event.currentTarget.setPointerCapture?.(event.pointerId);
        lastDrag.current = valueAt(event);
        onDrag(lastDrag.current);
      }}
      onPointerMove={(event: PointerEvent<HTMLDivElement>) => {
        if (dragging.current) {
          lastDrag.current = valueAt(event);
          onDrag(lastDrag.current);
        }
      }}
      onPointerUp={(event: PointerEvent<HTMLDivElement>) => {
        if (dragging.current) {
          dragging.current = false;
          onCommit(valueAt(event));
        }
      }}
      // A touch the browser took or lost never ends with a release. Ending the drag with the last
      // value the finger reached lets whoever holds it let go, instead of leaving it held for good.
      onPointerCancel={() => {
        if (dragging.current) {
          dragging.current = false;
          onCommit(lastDrag.current ?? value);
        }
      }}
      onLostPointerCapture={() => {
        if (dragging.current) {
          dragging.current = false;
          onCommit(lastDrag.current ?? value);
        }
      }}
      onKeyDown={onKeyDown}
    >
      {gradient ? (
        <span className={styles.dim} style={{ left: `${Math.max(pct, 4)}%` }} />
      ) : (
        <span className={styles.fill} style={{ width: `${pct}%` }} />
      )}
    </div>
  );
}

/** One round power icon button with two states: filled with the accent while on, neutral while off. */
export function PowerButton({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <button
      type="button"
      className={styles.power}
      aria-pressed={on}
      aria-label={on ? 'Turn off' : 'Turn on'}
      title={on ? 'Turn off' : 'Turn on'}
      onClick={onToggle}
    >
      <Icon size={16}>
        <Power />
      </Icon>
    </button>
  );
}

/** The height of every chip, whatever its label holds. */
export const CHIP_HEIGHT = 28;

/** A wrapping row of pill choices (modes, presets) with the selected one filled. With `tabs` it is
 * a tab list (the selected chip is the open tab) that never wraps: too many to fit scroll sideways. */
export function ChipRow({
  options,
  value,
  onChange,
  tabs = false,
}: {
  options: { value: string; label: string; icon?: ReactNode; ariaLabel?: string }[];
  value: string | undefined;
  onChange: (value: string) => void;
  tabs?: boolean;
}) {
  // The accent behind the selected chip is one pill that glides to the next, by CSS (`useGlide`).
  const chipRow = useRef<HTMLDivElement>(null);
  useGlide(chipRow, value);
  const still = useStill();
  // The selected tab is brought into view when it is not (it is a long name that has just opened, or a
  // tab part way out of the row), by scrolling the row so it is at its start.
  const marker = useRef<HTMLSpanElement>(null);
  const selectedLabel = options.find((option) => option.value === value)?.label;
  useEffect(() => {
    const chip = marker.current?.parentElement;
    const scroller = chip?.parentElement;
    if (!tabs || !chip || !scroller) {
      return;
    }

    const reveal = () => {
      const at = chip.getBoundingClientRect();
      const row = scroller.getBoundingClientRect();
      // Out of view includes under the fade at an edge that has more beyond it, which hides it nearly as much.
      const inLeft = row.left + (scroller.scrollLeft > 1 ? FADE : 0);
      const more = scroller.scrollLeft + scroller.clientWidth < scroller.scrollWidth - 1;
      const inRight = row.right - (more ? FADE : 0);
      if (at.left >= inLeft && at.right <= inRight) {
        return;
      }

      // To the start of the row, clear of the fade there (a position before the start is the start): glided by the browser where
      // it can, at once where nothing moves.
      const to = scroller.scrollLeft + (at.left - row.left) - FADE;
      if (still || typeof scroller.scrollTo !== 'function') {
        scroller.scrollLeft = to;
        return;
      }

      scroller.scrollTo({ left: to, behavior: 'smooth' });
    };

    reveal();
    // The row can get narrower after the tab is selected (the actions of what opened come in beside it).
    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(reveal);
    resize?.observe(scroller);
    return () => resize?.disconnect();
    // `selectedLabel` is not read inside: a new name for the selected tab is its length changing, which is a reason to look again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [tabs, value, selectedLabel, still]);

  const chips = (
    <>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <button
            type="button"
            key={option.value}
            className={styles.chip}
            data-selected={selected}
            {...(tabs ? { role: 'tab', 'aria-selected': selected } : { 'aria-pressed': selected })}
            {...(option.ariaLabel ? { 'aria-label': option.ariaLabel } : {})}
            onClick={() => onChange(option.value)}
          >
            {selected ? <span ref={marker} hidden /> : null}
            <span className={styles.content}>
              {option.icon ? <Icon size={14}>{option.icon}</Icon> : null}
              <span className={styles.chipLabel}>
                <MarqueeText>{option.label}</MarqueeText>
              </span>
            </span>
          </button>
        );
      })}
    </>
  );

  // Tabs scroll sideways when there are more than fit, and fade at the edge that has more.
  return tabs ? (
    <FadeScroll ref={chipRow} className={cx(styles.chips, styles.tabs)} role="tablist">
      <span className={styles.pill} aria-hidden="true" />
      {chips}
    </FadeScroll>
  ) : (
    <div ref={chipRow} className={cx(styles.chips, styles.wrapping)}>
      <span className={styles.pill} aria-hidden="true" />
      {chips}
    </div>
  );
}

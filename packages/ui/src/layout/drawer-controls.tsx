/** @jsxImportSource @emotion/react */
import { Box, Flex, Typography } from 'e-prim';
import { animate, motion } from 'motion/react';
import { MarqueeText } from './marquee-text.tsx';
import {
  useEffect,
  useId,
  useRef,
  type ElementType,
  type KeyboardEvent,
  type PointerEvent,
} from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { FADE, FadeScroll } from './fade-scroll.tsx';
import { RoundButton } from './round-button.tsx';
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
  icon: IconName;
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
      css={{ flex: 'none', ...(plain ? { background: 'none' } : {}) }}
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
      <Icon name={icon} size={16} />
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
    <Box
      role="slider"
      tabIndex={0}
      aria-label={label}
      aria-valuemin={min}
      aria-valuemax={max}
      aria-valuenow={value}
      position="relative"
      // Explicit width: the bar has no in-flow content (its fill is absolutely positioned), so as a
      // flex item in a row it would size to 0 and vanish.
      width="100%"
      height={height}
      radius="full"
      background={track}
      border={!gradient}
      cursor="pointer"
      overflow="hidden"
      css={{ touchAction: 'none', ...(gradient ? { backgroundImage: gradient } : {}) }}
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
      {/* The fill follows the drag, so it is set through motion's `animate` (instantly) instead of
          a class per value. */}
      {gradient ? (
        <Box
          as={motion.span}
          position="absolute"
          background="surface"
          initial={false}
          animate={{ left: `${Math.max(pct, 4)}%` }}
          transition={{ duration: 0 }}
          css={{ top: 0, bottom: 0, right: 0, opacity: 0.72, pointerEvents: 'none' }}
        />
      ) : (
        <Box
          as={motion.span}
          position="absolute"
          background="accent"
          initial={false}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0 }}
          css={{ top: 0, bottom: 0, left: 0 }}
        />
      )}
    </Box>
  );
}

/** One round power icon button with two states: filled with the accent while on, neutral while off. */
export function PowerButton({ on, onToggle }: { on: boolean; onToggle: () => void }) {
  return (
    <Flex
      as="button"
      type="button"
      aria-pressed={on}
      aria-label={on ? 'Turn off' : 'Turn on'}
      title={on ? 'Turn off' : 'Turn on'}
      onClick={onToggle}
      align="center"
      justify="center"
      radius="full"
      cursor="pointer"
      width={36}
      height={36}
      p={0}
      background={on ? 'accent' : 'surfaceRaised'}
      color={on ? 'accentText' : 'line'}
      css={{ flex: 'none', transition: 'background-color 0.2s ease, color 0.2s ease' }}
    >
      <Icon name="lu:power" size={16} />
    </Flex>
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
  options: { value: string; label: string; icon?: IconName; ariaLabel?: string }[];
  value: string | undefined;
  onChange: (value: string) => void;
  tabs?: boolean;
}) {
  // The accent behind the selected chip is one pill that glides to the next; its own id, so two rows
  // on a page do not share it.
  const pill = useId();
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

    let scroll: { stop: () => void } | undefined;
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

      scroll?.stop();
      const from = scroller.scrollLeft;
      if (still) {
        scroller.scrollLeft = from + (at.left - row.left) - FADE;
        return;
      }

      // To the start of the row, clear of the fade there (a position before the start is the start).
      scroll = animate(from, from + (at.left - row.left) - FADE, {
        duration: 0.35,
        ease: 'easeOut',
        onUpdate: (to) => {
          scroller.scrollLeft = to;
        },
      });
    };

    reveal();
    // The row can get narrower after the tab is selected (the actions of what opened come in beside it).
    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(reveal);
    resize?.observe(scroller);
    return () => {
      scroll?.stop();
      resize?.disconnect();
    };
    // `selectedLabel` is not read inside: a new name for the selected tab is its length changing, which is a reason to look again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [tabs, value, selectedLabel, still]);

  // Tabs scroll sideways when there are more than fit, and fade at the edge that has more.
  const Wrap: ElementType = tabs ? FadeScroll : Flex;
  return (
    <Wrap
      gap={2}
      {...(tabs ? { role: 'tablist' } : {})}
      css={
        tabs
          ? {
              flexShrink: 0,
              isolation: 'isolate',
              scrollbarWidth: 'none',
              '&::-webkit-scrollbar': { display: 'none' },
            }
          : { flexWrap: 'wrap', isolation: 'isolate' }
      }
    >
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Flex
            as="button"
            type="button"
            key={option.value}
            {...(tabs ? { role: 'tab', 'aria-selected': selected } : { 'aria-pressed': selected })}
            {...(option.ariaLabel ? { 'aria-label': option.ariaLabel } : {})}
            onClick={() => onChange(option.value)}
            align="center"
            gap={1.5}
            radius="full"
            cursor="pointer"
            // A fixed height, not the text's: an emoji is drawn from another font with a taller line,
            // which would make this one chip taller than the rest.
            height={CHIP_HEIGHT}
            px={3}
            background="surface"
            color={selected ? 'accentText' : 'text'}
            css={{
              flex: 'none',
              position: 'relative',
              whiteSpace: 'nowrap',
              transition: 'color 160ms ease',
            }}
          >
            {selected ? <span ref={marker} hidden /> : null}
            {selected ? (
              still ? (
                <Box
                  as="span"
                  position="absolute"
                  radius="full"
                  background="accent"
                  css={{ inset: 0, zIndex: 1 }}
                />
              ) : (
                <Box
                  as={motion.span}
                  layoutId={pill}
                  position="absolute"
                  radius="full"
                  background="accent"
                  transition={{ type: 'spring', duration: 0.32, bounce: 0.12 }}
                  // Above every chip's own background, whichever way it is going: a later chip paints over
                  // an earlier one's pill otherwise.
                  css={{ inset: 0, zIndex: 1 }}
                />
              )
            ) : null}
            {/* Above the pill. */}
            <Flex
              as="span"
              align="center"
              gap={1.5}
              position="relative"
              minWidth={0}
              css={{ zIndex: 2 }}
            >
              {option.icon ? <Icon name={option.icon} size={14} /> : null}
              <Typography as="span" variant="body" css={{ minWidth: 0, maxWidth: 220 }}>
                <MarqueeText>{option.label}</MarqueeText>
              </Typography>
            </Flex>
          </Flex>
        );
      })}
    </Wrap>
  );
}

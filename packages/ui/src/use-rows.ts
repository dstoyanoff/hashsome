import { useAnimates } from './motion/mode.ts';
import {
  startTransition,
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type UIEvent,
} from 'react';

/** How many rows of a list are drawn to begin with, and how many more each time the end comes near: a library can be
 * hundreds of rows long, and drawing them all at once is seconds of work on a slow display, for rows nobody has scrolled to. */
export const FIRST_ROWS = 10;
const MORE_ROWS = 10;

/** A list opens with `OPENING_ROWS` and grows by a row every `STEP_MS` up to `FIRST_ROWS`, so no frame draws more than a row
 * or two (a screenful at once is a pause on a slow display) and the rows arrive one after another, each sliding in from the right as it does. */
const OPENING_ROWS = 1;
const WHOLE_ROWS = 4;
const STEP_ROWS = 1;

/** The time between one row and the next while the list opens, in ms: long enough to see each arrive. */
const STEP_MS = 55;

/** The rows of a long list as far as they are wanted (`shown`): the first screenful, and more as `onScroll` finds the
 * end close. Another list (`key`) starts over. */
export function useRows<T>(items: readonly T[], key: string) {
  // Where nothing moves, there is no arriving to see: the first screenful is drawn at once.
  const animates = useAnimates();
  const opening = animates ? OPENING_ROWS : FIRST_ROWS;
  const [count, setCount] = useState(opening);
  const [of, setOf] = useState(key);
  if (of !== key) {
    setOf(key);
    setCount(opening);
  }

  const list = useRef<HTMLElement | null>(null);
  const total = items.length;
  const more = useCallback(() => {
    const el = list.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight < 200) {
      // A transition: the next rows are not what was asked for, so drawing them must not hold up what was.
      startTransition(() => setCount((now) => Math.min(total, now + MORE_ROWS)));
    }
  }, [total]);

  // The first screenful comes a couple of rows by one, a beat apart.
  useEffect(() => {
    if (count < Math.min(total, FIRST_ROWS)) {
      const timer = setTimeout(
        () => setCount((now) => Math.min(total, FIRST_ROWS, now + STEP_ROWS)),
        STEP_MS,
      );

      return () => clearTimeout(timer);
    }
  }, [count, total]);

  // A list shorter than its room has no scrolling to ask with, so what is missing is drawn at once.
  useEffect(() => {
    if (count >= FIRST_ROWS && count < total) {
      const frame = requestAnimationFrame(more);
      return () => cancelAnimationFrame(frame);
    }
  }, [count, total, more]);

  // The room of the rows still to come is kept, so the list is as tall as it will be from the first row on and growing it does not lay
  // out everything around it again at every step. A row's height is read from the last one drawn, which is what the spacer follows.
  const target = Math.min(total, FIRST_ROWS);
  const pending = total <= WHOLE_ROWS ? 0 : Math.max(0, target - count);
  const spacer = useRef<HTMLLIElement | null>(null);
  const [measure, setMeasure] = useState({ row: 0, gap: 0 });
  useLayoutEffect(() => {
    const last = spacer.current?.previousElementSibling;
    const parent = spacer.current?.parentElement;
    if (last instanceof HTMLElement && parent && last.offsetHeight > 0) {
      const gap = Number.parseFloat(getComputedStyle(parent).rowGap) || 0;
      setMeasure((now) =>
        now.row === last.offsetHeight && now.gap === gap ? now : { row: last.offsetHeight, gap },
      );
    }
  }, [count, pending]);

  const attach = useCallback((el: HTMLElement | null) => {
    list.current = el;
  }, []);

  const onScroll = useCallback((_event: UIEvent<HTMLElement>) => more(), [more]);

  // A list of a few rows is drawn whole: there is nothing to spread out. (Decided by what the list holds now, not when it opened: it
  // may have been empty then, still on its way.)
  const whole = count >= total || total <= WHOLE_ROWS;
  // What to spread onto an empty `<li aria-hidden>` right after the rows: `pending` rows and the gaps between them, less the gap the
  // spacer's own place in the list adds. Always drawn (so there is one to measure from), but not there while there is nothing to keep.
  const keeping = pending > 0 && measure.row > 0;
  const reserve = {
    ref: spacer,
    'aria-hidden': true as const,
    style: {
      display: keeping ? 'block' : 'none',
      flex: 'none',
      height: keeping ? pending * (measure.row + measure.gap) - measure.gap : 0,
    } satisfies CSSProperties,
  };

  return { shown: whole ? items : items.slice(0, count), attach, onScroll, reserve };
}

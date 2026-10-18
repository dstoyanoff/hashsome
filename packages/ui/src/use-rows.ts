import { startTransition, useCallback, useEffect, useRef, useState, type UIEvent } from 'react';

/** How many rows of a list are drawn to begin with, and how many more each time the end comes near: a library can be
 * hundreds of rows long, and drawing them all at once is seconds of work on a slow display, for rows nobody has scrolled to.
 * Five is about what a small display's sheet shows, and the first draw costs about 2 ms a row there: the rest follows in a
 * transition (see `more`). */
export const FIRST_ROWS = 5;
const MORE_ROWS = 5;

/** The rows of a long list as far as they are wanted (`shown`): the first screenful, and more as `onScroll` finds the
 * end close. Another list (`key`) starts over. */
export function useRows<T>(items: readonly T[], key: string) {
  const [count, setCount] = useState(FIRST_ROWS);
  const [of, setOf] = useState(key);
  if (of !== key) {
    setOf(key);
    setCount(FIRST_ROWS);
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

  // A list shorter than its room has no scrolling to ask with, so what is missing is drawn at once.
  useEffect(() => {
    if (count < total) {
      const frame = requestAnimationFrame(more);
      return () => cancelAnimationFrame(frame);
    }
  }, [count, total, more]);

  const attach = useCallback((el: HTMLElement | null) => {
    list.current = el;
  }, []);

  const onScroll = useCallback((_event: UIEvent<HTMLElement>) => more(), [more]);

  return { shown: count >= total ? items : items.slice(0, count), attach, onScroll };
}

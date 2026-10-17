import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ComponentPropsWithRef,
  type ElementType,
} from 'react';
import { cx } from '../style/cx.ts';
import styles from './fade-scroll.module.css';

/** How wide the fade at a scrolled edge is. */
export const FADE = 40;

/**
 * A row that scrolls sideways and fades out at an edge that has more content beyond it, so it
 * reads as scrollable: the right edge while there is more to the right, the left once scrolled.
 * It is a flex row that sets `overflow-x: auto`; `as`, `className` and the element's own props apply (hide or
 * style the scrollbar with a class of your own). Nothing fades when it all fits. `data-fade-start` and `data-fade-end`
 * say which edges are fading.
 */
export function FadeScroll<E extends ElementType = 'div'>({
  as,
  className,
  ...props
}: { as?: E } & Omit<ComponentPropsWithRef<E>, 'as'>) {
  const Tag: ElementType = as ?? 'div';
  const ref = useRef<HTMLElement>(null);
  const [edges, setEdges] = useState({ start: false, end: false });
  const measure = useCallback(() => {
    const el = ref.current;
    if (!el) {
      return;
    }

    const start = el.scrollLeft > 1;
    const end = el.scrollLeft + el.clientWidth < el.scrollWidth - 1;
    setEdges((now) => (now.start === start && now.end === end ? now : { start, end }));
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }

    measure();
    el.addEventListener('scroll', measure, { passive: true });
    // Its size, and what is in it (items arriving, artwork loading), both change how far it scrolls.
    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(measure);
    resize?.observe(el);
    const mutation =
      typeof MutationObserver === 'undefined' ? undefined : new MutationObserver(measure);

    mutation?.observe(el, { childList: true, subtree: true });
    el.addEventListener('load', measure, true);
    return () => {
      el.removeEventListener('scroll', measure);
      el.removeEventListener('load', measure, true);
      resize?.disconnect();
      mutation?.disconnect();
    };
  }, [measure]);

  return (
    <Tag
      ref={ref}
      data-fade-start={edges.start}
      data-fade-end={edges.end}
      className={cx(styles.scroll, className as string | undefined)}
      {...props}
    />
  );
}

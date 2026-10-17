/** @jsxImportSource @emotion/react */
import type { Interpolation, Theme } from '@emotion/react';
import { Flex, type FlexProps } from '../style/prim.tsx';
import { useCallback, useEffect, useRef, useState, type ElementType } from 'react';

/** How wide the fade at a scrolled edge is. */
export const FADE = 40;

const maskFor = (start: boolean, end: boolean) =>
  `linear-gradient(to right, ${start ? 'transparent' : '#000'} 0, #000 ${start ? FADE : 0}px, #000 calc(100% - ${end ? FADE : 0}px), ${end ? 'transparent' : '#000'} 100%)`;

/**
 * A row that scrolls sideways and fades out at an edge that has more content beyond it, so it
 * reads as scrollable: the right edge while there is more to the right, the left once scrolled.
 * It is a `Flex` (so `as`, `gap` and the rest apply) and sets `overflow-x: auto`; hide or style the
 * scrollbar with its own `css`. Nothing fades when it all fits. `data-fade-start` and `data-fade-end`
 * say which edges are fading.
 */
export function FadeScroll<E extends ElementType = 'div'>({
  css,
  ...props
}: FlexProps<E> & { css?: Interpolation<Theme> }) {
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

  const mask = edges.start || edges.end ? maskFor(edges.start, edges.end) : undefined;
  const own = {
    ref,
    'data-fade-start': edges.start,
    'data-fade-end': edges.end,
    css: [
      { overflowX: 'auto' },
      mask ? { maskImage: mask, WebkitMaskImage: mask } : null,
      css ?? null,
    ],
    ...props,
  } as FlexProps<ElementType>;

  return <Flex {...own} />;
}

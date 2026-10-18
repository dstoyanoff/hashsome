import { useLayoutEffect, useRef, type RefObject } from 'react';

/**
 * Lets one marker glide to whichever child of a row is selected (`data-selected="true"`), by CSS: the position and size of the selected
 * child are written as `--glide-x`, `--glide-y`, `--glide-w` (and `--glide-n`, the width with no unit) and `--glide-h` on the row, and the marker (positioned in the row, sized
 * by those) has a `transition` on them while the row says `data-glide="on"`.
 *
 * It glides only when the selection is changed (`selection` is another). Where it is first put, and where it is put again because the
 * row or the selected child changed size (a page still laying out, a late font or stylesheet, a window resize), it is just there: a
 * marker that travelled to its place at those moments would come from wherever it was first measured, which is not where anyone saw it.
 */
export function useGlide(row: RefObject<HTMLElement | null>, selection: unknown): void {
  const placed = useRef(false);
  useLayoutEffect(() => {
    const host = row.current;
    if (!host) {
      return;
    }

    const chip = host.querySelector<HTMLElement>('[data-selected="true"]');
    const place = (glide: boolean) => {
      const next = chip
        ? {
            x: chip.offsetLeft,
            y: chip.offsetTop,
            w: chip.offsetWidth,
            h: chip.offsetHeight,
          }
        : { x: 0, y: 0, w: 0, h: 0 };

      // Nothing to do when it is where it should be already, which also keeps a running glide from being cut off by the observer
      // below, that reports the first size it sees.
      if (
        glide === false &&
        host.style.getPropertyValue('--glide-n') === `${next.w}` &&
        host.style.getPropertyValue('--glide-x') === `${next.x}px` &&
        host.style.getPropertyValue('--glide-y') === `${next.y}px` &&
        host.style.getPropertyValue('--glide-h') === `${next.h}px`
      ) {
        return;
      }

      if (glide) {
        host.setAttribute('data-glide', 'on');
      } else {
        host.removeAttribute('data-glide');
      }

      host.style.setProperty('--glide-x', `${next.x}px`);
      host.style.setProperty('--glide-y', `${next.y}px`);
      host.style.setProperty('--glide-w', `${next.w}px`);
      // The same, with no unit, for a marker that is scaled to the width instead of sized (a transform needs no layout to animate).
      host.style.setProperty('--glide-n', `${next.w}`);
      host.style.setProperty('--glide-h', `${next.h}px`);
    };

    // Measured with no glide at first; with one for every selection after that.
    place(placed.current);
    placed.current = true;

    const resize =
      typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(() => place(false));

    resize?.observe(host);
    if (chip) {
      resize?.observe(chip);
    }

    return () => resize?.disconnect();
    // `selection` is not read inside: a new one is the selected child being another, which is a reason to measure again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [row, selection]);
}

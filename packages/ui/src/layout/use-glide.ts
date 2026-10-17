import { useLayoutEffect, type RefObject } from 'react';

/**
 * Lets one marker glide to whichever child of a row is selected (`data-selected="true"`), by CSS: the position and size of the selected
 * child are written as `--glide-x`, `--glide-y`, `--glide-w` and `--glide-h` on the row, and the marker (positioned in the row, sized
 * by those) has a `transition` on them. Measured again when the selection changes and when the row or the selected child changes size.
 * `data-glide="on"` is set a frame after the first placement, so the marker starts where it belongs and only later moves.
 */
export function useGlide(row: RefObject<HTMLElement | null>, selection: unknown): void {
  useLayoutEffect(() => {
    const host = row.current;
    if (!host) {
      return;
    }

    const chip = host.querySelector<HTMLElement>('[data-selected="true"]');
    const place = () => {
      if (!chip) {
        host.style.setProperty('--glide-w', '0px');
        return;
      }

      host.style.setProperty('--glide-x', `${chip.offsetLeft}px`);
      host.style.setProperty('--glide-y', `${chip.offsetTop}px`);
      host.style.setProperty('--glide-w', `${chip.offsetWidth}px`);
      host.style.setProperty('--glide-h', `${chip.offsetHeight}px`);
    };

    place();
    const frame = requestAnimationFrame(() => {
      host.dataset['glide'] = 'on';
    });

    const resize = typeof ResizeObserver === 'undefined' ? undefined : new ResizeObserver(place);
    resize?.observe(host);
    if (chip) {
      resize?.observe(chip);
    }

    return () => {
      cancelAnimationFrame(frame);
      resize?.disconnect();
    };
    // `selection` is not read inside: a new one is the selected child being another, which is a reason to measure again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [row, selection]);
}

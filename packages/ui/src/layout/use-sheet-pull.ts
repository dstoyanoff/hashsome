import { useRef, type PointerEvent, type RefObject } from 'react';

/** How far a sheet has to be pulled down, or how fast, to be let go. */
const DISMISS_DISTANCE = 100;
const DISMISS_VELOCITY = 0.5;

/**
 * Lets a bottom sheet be pulled down by its handle and let go when it has been pulled far or fast enough, otherwise it springs
 * back: the pointer handlers for the handle. The sheet follows the finger through its `translate` (a property of its own, apart
 * from the `transform` its entrance and exit animations use), set as `--pull` so nothing is drawn again.
 */
export function useSheetPull(sheet: RefObject<HTMLElement | null>, release: () => void) {
  const state = useRef<{ start: number; last: number; at: number; speed: number } | null>(null);
  const move = (offset: number, ease: boolean) => {
    const node = sheet.current;
    if (!node) {
      return;
    }

    // The sheet is a DOM node this hook is lent, moved by hand while a finger is on it: React draws none of this.
    // oxlint-disable-next-line react/immutability
    node.style.transition = ease ? 'translate 0.2s ease-out' : 'none';
    // oxlint-disable-next-line react/immutability
    node.style.translate = `0 ${offset}px`;
  };

  return {
    onPointerDown: (event: PointerEvent<HTMLElement>) => {
      event.currentTarget.setPointerCapture?.(event.pointerId);
      state.current = { start: event.clientY, last: event.clientY, at: event.timeStamp, speed: 0 };
    },
    onPointerMove: (event: PointerEvent<HTMLElement>) => {
      const pulling = state.current;
      if (!pulling) {
        return;
      }

      const elapsed = Math.max(1, event.timeStamp - pulling.at);
      pulling.speed = (event.clientY - pulling.last) / elapsed;
      pulling.last = event.clientY;
      pulling.at = event.timeStamp;
      // Only down: up is not a way to go, and a little give past the top would be a second thing to learn.
      move(Math.max(0, event.clientY - pulling.start), false);
    },
    onPointerUp: (event: PointerEvent<HTMLElement>) => {
      const pulling = state.current;
      state.current = null;
      if (!pulling) {
        return;
      }

      const offset = Math.max(0, event.clientY - pulling.start);
      if (offset > DISMISS_DISTANCE || pulling.speed > DISMISS_VELOCITY) {
        release();
        return;
      }

      move(0, true);
    },
    onPointerCancel: () => {
      state.current = null;
      move(0, true);
    },
  };
}

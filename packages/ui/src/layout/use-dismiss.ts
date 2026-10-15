import { useEffect, type RefObject } from 'react';

/** Closes a popover (calls `close`) on a press outside `ref` or on Escape, while it is `open`. */
export function useDismiss(open: boolean, close: () => void, ref: RefObject<HTMLElement | null>) {
  useEffect(() => {
    if (!open) {
      return;
    }

    const onPointer = (event: PointerEvent) => {
      if (!ref.current?.contains(event.target as Node)) {
        close();
      }
    };

    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        close();
      }
    };

    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
    // `close` is a fresh function each render but only ever sets state; re-subscribing for it would
    // do nothing but churn listeners.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, [open, ref]);
}

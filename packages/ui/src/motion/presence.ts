import { useEffect, useState } from 'react';
import { useFullMotion } from './mode.ts';

/**
 * Keeps something drawn for a moment after it is meant to be gone, so its exit can play (by CSS, on `data-leaving`): `mounted` is
 * whether to draw it at all and `leaving` whether it is on its way out. Where nothing moves there is no exit and it is gone at once.
 */
export function usePresence(
  present: boolean,
  exitMs: number,
): { mounted: boolean; leaving: boolean } {
  const full = useFullMotion();
  const [mounted, setMounted] = useState(present);
  if (present && !mounted) {
    setMounted(true);
  }

  useEffect(() => {
    if (present || !mounted || !full) {
      return;
    }

    const timer = setTimeout(() => setMounted(false), exitMs);
    return () => clearTimeout(timer);
  }, [present, mounted, full, exitMs]);

  // Gone at once where nothing moves, without waiting for anything.
  return { mounted: present || (mounted && full), leaving: !present && mounted && full };
}

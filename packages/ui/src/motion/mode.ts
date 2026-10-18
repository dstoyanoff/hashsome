import { useSyncExternalStore } from 'react';

/** How much the app moves: `full` runs the animation library, `light` keeps the few animations that are CSS, `reduced` keeps none.
 * Set once when the app starts (`HashsomeProvider`), from its `motion` prop or the address (`?motion=`). */
export type MotionLevel = 'full' | 'light' | 'reduced';

let level: MotionLevel = 'full';

export function setMotionLevel(next: MotionLevel): void {
  level = next;
}

export const motionLevel = (): MotionLevel => level;

const QUERY = '(prefers-reduced-motion: reduce)';

function subscribe(listener: () => void): () => void {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return () => undefined;
  }

  const media = window.matchMedia(QUERY);
  media.addEventListener('change', listener);
  return () => media.removeEventListener('change', listener);
}

const asked = (): boolean =>
  typeof window !== 'undefined' &&
  typeof window.matchMedia === 'function' &&
  window.matchMedia(QUERY).matches;

/** Whether nothing should move: the person asked their device for less motion, or the app runs in `light` or `reduced` motion
 * (`?motion=`, for a display too slow to animate). A component then draws no motion element at all, not one whose animations
 * are skipped, and the animation library is not even fetched. */
export function useStill(): boolean {
  const prefers = useSyncExternalStore(subscribe, asked, () => false);
  return prefers || level !== 'full';
}

/** Whether the animations that cost something run: the app is in full motion and the device has not asked for less. */
export function useFullMotion(): boolean {
  return !useStill();
}

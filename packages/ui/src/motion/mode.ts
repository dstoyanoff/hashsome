import { useSyncExternalStore } from 'react';

/** How much the app moves, all of it CSS: `reduced` none, `light` the cheap ones that play as something appears, `full` those and more.
 * Set when the app starts (`HashsomeProvider`'s `motion` prop), by a dashboard (`Motion`), or by the address (`?motion=`). */
export type MotionLevel = 'full' | 'light' | 'reduced';

let level: MotionLevel = 'full';

export function setMotionLevel(next: MotionLevel): void {
  level = next;
}

export const motionLevel = (): MotionLevel => level;

/** What the address asked for when the page opened: kept, because moving between a dashboard's pages (a rail's links have no query)
 * or between dashboards leaves it out of the address, and the display must not go back to what its layout says. */
let fromAddress: MotionLevel | undefined | 'unread' = 'unread';

/** What the address asks for motion: `?motion=reduced`, `?motion=light` or `?motion=full`, which wins over what the app or a dashboard
 * says, for trying one out. Read once, the first time it is asked for, and held until the page is reloaded without it. */
export function motionFromAddress(): MotionLevel | undefined {
  if (fromAddress === 'unread') {
    try {
      const value = new URLSearchParams(window.location.search).get('motion');
      fromAddress =
        value === 'reduced' || value === 'light' || value === 'full' ? value : undefined;
    } catch {
      return undefined;
    }
  }

  return fromAddress;
}

/** For tests: read the address again the next time it is asked for. */
export function forgetMotionAddress(): void {
  fromAddress = 'unread';
}

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

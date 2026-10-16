import { MotionGlobalConfig, useReducedMotion } from 'motion/react';

/** Whether nothing should move: the person asked their device for less motion, or the app runs in `reduced` motion
 * (`?motion=reduced`, for a display too slow to animate). A component then draws no motion element at all, not one
 * whose animations are skipped: the element is most of what a slow display pays for. */
export function useStill(): boolean {
  const asked = useReducedMotion();
  return asked === true || MotionGlobalConfig.skipAnimations === true;
}

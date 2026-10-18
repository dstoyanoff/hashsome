import { useLayoutEffect, type ReactNode } from 'react';
import { motionFromAddress, setMotionLevel, type MotionLevel } from '../motion/mode.ts';

export interface MotionProps {
  /** How much this dashboard moves: `'reduced'` none, `'light'` the cheap animations that play as something appears (for a slow wall display), `'full'` all of them. The address can still ask for another (`?motion=`), for trying one out. */
  level: MotionLevel;

  /** The dashboard it is for. */
  children?: ReactNode;
}

/**
 * Says how much a dashboard moves, for the display it is made for: wrap the dashboard's layout in it (a wall display's takes
 * `light`, a tablet's or a phone's can have all of it). It lasts while the dashboard is shown, and what the app was set to
 * (`HashsomeProvider`'s `motion`) comes back when another one is. Draws only its children.
 */
export function Motion({ level, children }: MotionProps): ReactNode {
  const chosen = motionFromAddress() ?? level;
  // Before the dashboard under it is drawn, so what it does in JavaScript (a list staggering in) goes by it from the first render.
  setMotionLevel(chosen);
  useLayoutEffect(() => {
    const root = document.documentElement;
    const before = root.dataset['motion'];
    root.dataset['motion'] = chosen;
    return () => {
      if (before === 'full' || before === 'light' || before === 'reduced') {
        root.dataset['motion'] = before;
        setMotionLevel(before);
      }
    };
  }, [chosen]);

  return children;
}

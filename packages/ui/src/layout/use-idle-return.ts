import { createContext, useContext, useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router';

/** The app-wide time a display is left alone before it goes back to a dashboard's main page, set by
 * `HashsomeProvider`'s `idleReturn`. `false`: it does not. */
export const IdleReturnContext = createContext<number | false>(false);

/** How often the page checks whether it has been left alone long enough, at most. */
const CHECK_MS = 10_000;

/** What counts as someone being there: a touch or a click, a key, a scroll or a wheel anywhere on the page. */
const INTERACTIONS = [
  'pointerdown',
  'pointermove',
  'keydown',
  'wheel',
  'touchstart',
  'scroll',
] as const;

/** Whether it is time to go back: nothing has been touched for `after`. */
export function shouldLeave(now: number, lastTouched: number, after: number): boolean {
  return now - lastTouched >= after;
}

/** The app's own setting for how long a display is left alone before it goes back to a main page: what
 * `HashsomeProvider`'s `idleReturn` says, `false` when it says nothing. */
export function useIdleReturnDefault(): number | false {
  return useContext(IdleReturnContext);
}

/**
 * Takes a wall display back to `to` (a dashboard's main page) once nobody has touched it for `after` ms,
 * so that a page opened for a while (a music page, say) does not stay up for hours. Any touch, click, key
 * or scroll anywhere starts the time again; it is counted from arriving on a page that is not `to`, and
 * on `to` itself nothing happens. The page it leaves replaces itself in the history, so the back button does
 * not return to it. `false` (or no time) turns it off.
 *
 * `NavRail`, `NavDock` and `NavBar` do this for the dashboard they are in (see their `idleReturn`); use the hook
 * directly for a dashboard that builds its own navigation.
 */
export function useIdleReturn({ to, after }: { to: string; after: number | false | undefined }) {
  const navigate = useNavigate();
  const { pathname, search } = useLocation();
  // The page it goes back to is opened with the query the display was, a flag (`?motion=reduced`) it was set up with.
  const destination = search === '' || to.includes('?') ? to : `${to}${search}`;
  // Set when the time starts, in the effect below.
  const lastTouched = useRef(0);
  const enabled = typeof after === 'number' && after > 0;
  const away = pathname.replace(/\/$/, '') !== to.replace(/\/$/, '');

  useEffect(() => {
    if (!enabled || !away) {
      return;
    }

    lastTouched.current = Date.now();
    const touched = () => {
      lastTouched.current = Date.now();
    };

    for (const name of INTERACTIONS) {
      window.addEventListener(name, touched, { passive: true, capture: true });
    }

    const check = setInterval(
      () => {
        if (shouldLeave(Date.now(), lastTouched.current, after)) {
          navigate(destination, { replace: true });
        }
      },
      Math.min(CHECK_MS, after / 2),
    );

    return () => {
      for (const name of INTERACTIONS) {
        window.removeEventListener(name, touched, { capture: true });
      }

      clearInterval(check);
    };
  }, [enabled, away, after, destination, navigate]);
}

import { useLocation } from 'react-router';

/**
 * Makes the addresses a dashboard navigates to keep the query string the page is under (`?motion=reduced`, `?grid`, a debug flag):
 * moving between its pages or to another dashboard must not drop what the display was opened with. `NavRail`, `NavDock`, `NavBar`,
 * the top bar's dashboard switcher and the idle return do this already; use it for a link or a `navigate` of a dashboard's own.
 *
 * ```tsx
 * const keep = useKeepSearch();
 * navigate(keep('/bathroom/music'));
 * ```
 */
export function useKeepSearch(): (to: string) => string {
  const { search } = useLocation();
  return (to) => (search === '' || to.includes('?') ? to : `${to}${search}`);
}

/** Whether the page runs as an app added to the home screen (a standalone window) and not in a browser tab. Safari
 * says so with its own `navigator.standalone`, other browsers with the `display-mode` media query. */
export function isInstalledApp(): boolean {
  if (typeof window === 'undefined') {
    return false;
  }

  return (
    (navigator as { standalone?: boolean }).standalone === true ||
    window.matchMedia?.('(display-mode: standalone)').matches === true
  );
}

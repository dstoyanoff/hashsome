import { useInsertionEffect } from 'react';

const ID = 'hashsome-theme';

/** Installs the CSS custom properties of the theme (see `variables.ts`) as the page's one `<style>`, replacing it as the theme,
 * density or overrides change. An insertion effect: it is in place before anything is painted with it. */
export function useThemeStyle(css: string): void {
  useInsertionEffect(() => {
    let element = document.getElementById(ID) as HTMLStyleElement | null;
    if (!element) {
      element = document.createElement('style');
      element.id = ID;
      document.head.appendChild(element);
    }

    element.textContent = css;
  }, [css]);
}

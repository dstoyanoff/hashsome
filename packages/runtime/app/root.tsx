import { DEFAULT_FONT, fontStack, googleFontHref, type ThemeMode } from '@hashsome/ui';
import { Links, Meta, Scripts, ScrollRestoration } from 'react-router';

const SHELL = {
  dark: { bg: '#101114', fg: '#e6e6e6' },
  light: { bg: '#f4f1eb', fg: '#211e26' },
};

/** What makes the document an app a phone can put on its home screen. */
export interface AppOptions {
  /** The app's name, under its icon and in the app switcher. */
  name: string;

  /** A shorter name for under the icon, when the name is long. Default: `name`. */
  shortName?: string;

  /** The icon, as a path of the project's `public/` folder, e.g. `/icon-180.png`: a square picture of at least 180 px, without rounded corners (the phone rounds them). */
  icon?: string;

  /** Where the web app manifest is served from: a file of the project's `public/` folder, `/manifest.webmanifest` by default. `false` for none (iOS still opens the page as an app from the tags). */
  manifest?: string | false;
}

/** The tags in the document's `<head>` that make it an installable web app: standalone (no browser bar) once added
 * to the home screen, its name and icon, the colour of the status bar, and the manifest. Nothing when there is no `app`. */
export function appHead(app: AppOptions | undefined, theme: ThemeMode = 'dark') {
  if (!app) {
    return null;
  }

  const manifest = app.manifest === undefined ? '/manifest.webmanifest' : app.manifest;
  const dark = theme === 'dark';
  return (
    <>
      <meta name="mobile-web-app-capable" content="yes" />
      <meta name="apple-mobile-web-app-capable" content="yes" />
      <meta name="apple-mobile-web-app-title" content={app.shortName ?? app.name} />
      <meta name="apple-mobile-web-app-status-bar-style" content={dark ? 'black' : 'default'} />
      <meta name="theme-color" content={dark ? SHELL.dark.bg : SHELL.light.bg} />
      {app.icon ? <link rel="apple-touch-icon" href={app.icon} /> : null}
      {manifest ? <link rel="manifest" href={manifest} /> : null}
    </>
  );
}

/** Builds the document shell's `Layout`, painted before any client JS runs — so its background
 * needs to already roughly match the configured theme, or hydration visibly swaps the color out
 * from under the user. `'system'` can't know the OS preference at render time, so it ships both
 * colors and lets a plain CSS media query (not JS) pick the right one with no flash either way. A
 * schedule (by the clock or the sun) is not known yet either, so it starts as `'system'` does and
 * the app takes over once it is running.
 * The Google Fonts `<link>` is rendered here too (not left to `HashsomeProvider`'s own client-side
 * fallback) so the chosen font is already loading before hydration, not swapped in after.
 * With `app`, the document is also a web app a phone can add to its home screen (see `AppOptions`). */
export function createLayout(
  theme: ThemeMode = 'dark',
  font: string = DEFAULT_FONT,
  app?: AppOptions,
) {
  const followsSystem = typeof theme === 'object' || theme === 'system';
  const colorScheme = followsSystem ? 'light dark' : theme;
  const initial = theme === 'light' ? SHELL.light : SHELL.dark;
  const systemOverride = followsSystem
    ? `@media (prefers-color-scheme: light) { html, body { background: ${SHELL.light.bg}; color: ${SHELL.light.fg}; } }`
    : '';

  const kioskCss = `
    html, body { margin: 0; height: 100%; background: ${initial.bg}; color: ${initial.fg}; font-family: ${fontStack(font)}; }
    body { overscroll-behavior: none; -webkit-tap-highlight-color: transparent; user-select: none; }
    #root, body > div { min-height: 100%; }
    ${systemOverride}
  `;

  return function Layout({ children }: { children: React.ReactNode }) {
    return (
      <html lang="en">
        <head>
          <meta charSet="utf-8" />
          <meta
            name="viewport"
            content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover"
          />
          <meta name="color-scheme" content={colorScheme} />
          {appHead(app, theme)}
          <link rel="preconnect" href="https://fonts.googleapis.com" />
          <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="" />
          <link rel="stylesheet" href={googleFontHref(font)} data-hashsome-font="" />
          <style dangerouslySetInnerHTML={{ __html: kioskCss }} />
          <Meta />
          <Links />
        </head>
        <body>
          {children}
          <ScrollRestoration />
          <Scripts />
        </body>
      </html>
    );
  };
}

/** Default shell, equivalent to `createLayout('dark', 'Inter')`. A project that picks another theme
 * or font should export `createLayout(theme, font)` as its own `Layout` instead (see
 * `example/app/root.tsx`). */
export const Layout = createLayout('dark');

export function HydrateFallback() {
  return null;
}

import { createLayout, HydrateFallback } from '@hashsome/runtime/app';
import { HashsomeProvider, Page, type ThemeOverrides } from '@hashsome/ui';
import { Outlet } from 'react-router';

// App-wide look. `THEME` is `'light' | 'dark' | 'system'` (follows the OS and updates live);
// `FONT` is `'Inter'` (bundled with the app) or any Google Fonts family name (`'Roboto'`, `'Poppins'`, ...), loaded for you.
const THEME = 'system';
const FONT = 'Inter';

// Change any built-in token without touching `@hashsome/ui`; name only what differs. For example:
//   light: { palette: { accent: '#2d6cdf' } },       // colors, per theme
//   shared: { radius: { card: '16px' } },            // radii, typography, shadows, spacing
//   density: { comfortable: { space: 16 } },         // sizes that follow the display (spacing too)
const OVERRIDES: ThemeOverrides = {
  light: { palette: { accent: '#2d6cdf' } },
  dark: { palette: { accent: '#5b8def' } },
  shared: { radius: { card: '16px' } },
};

export const Layout = createLayout(THEME, FONT);
export { HydrateFallback };

// `Page` is what every dashboard renders into: padded, a column with gaps, viewport height, and
// scrolling inside itself. Swap it for your own wrapper if you want a different page.
export default function Root() {
  return (
    <HashsomeProvider theme={THEME} font={FONT} overrides={OVERRIDES}>
      <Page>
        <Outlet />
      </Page>
    </HashsomeProvider>
  );
}

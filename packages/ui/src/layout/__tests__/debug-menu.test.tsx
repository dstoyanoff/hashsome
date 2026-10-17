import { LocalClient, MockIntegration, mockSensor } from '@hashsome/core';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Box } from 'e-prim';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HashsomeProvider, type ThemeMode } from '../../provider.tsx';
import { Page } from '../page.tsx';
import { computed } from '../../test-utils.tsx';

const DARK_BG = 'rgb(11, 10, 13)';
const LIGHT_BG = 'rgb(244, 241, 235)';

beforeEach(() => localStorage.clear());
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
});

function Probe() {
  return <Box background="bg" data-testid="probe" />;
}

const SUN = 'ha:sun.sun';

/** `sunIs` is what the sun entity says; `sun` is the provider's prop naming it. */
function mount(
  props: { debug?: boolean; theme?: ThemeMode; sun?: string; sunIs?: 'on' | 'off' } = {},
) {
  const { sunIs = 'on', sun, ...rest } = props;
  const client = new LocalClient([
    new MockIntegration({
      entities: { 'sun.sun': mockSensor({ name: 'Sun', value: sunIs, measurement: 'daylight' }) },
    }),
  ]);

  return render(
    <HashsomeProvider
      client={client}
      theme="dark"
      {...(sun ? { sun: sun as `${string}:${string}` } : {})}
      {...rest}
    >
      <Page>
        <Probe />
      </Page>
    </HashsomeProvider>,
  );
}

const bg = () => getComputedStyle(screen.getByTestId('probe')).backgroundColor;
const open = () => fireEvent.click(screen.getByRole('button', { name: 'Debug menu' }));
const choose = (row: string, name: string) =>
  fireEvent.click(
    [...screen.getByRole('dialog').querySelectorAll('button')].find(
      (button) =>
        button.textContent === name &&
        button.closest('div')?.parentElement?.textContent?.startsWith(row),
    )!,
  );

test('there is no debug menu unless it is switched on', () => {
  mount();
  expect(screen.queryByRole('button', { name: 'Debug menu' })).toBeNull();
});

test('the runtime switches it on, by the constant it sets from HASHSOME_DEBUG', () => {
  vi.stubGlobal('HASHSOME_DEBUG_ON', true);
  mount();
  expect(screen.getByRole('button', { name: 'Debug menu' })).toBeTruthy();
  cleanup();

  // `debug` on the provider still says otherwise.
  mount({ debug: false });
  expect(screen.queryByRole('button', { name: 'Debug menu' })).toBeNull();
  cleanup();

  vi.stubGlobal('HASHSOME_DEBUG_ON', false);
  mount();
  expect(screen.queryByRole('button', { name: 'Debug menu' })).toBeNull();
  vi.unstubAllGlobals();
});

test('a floating button at the bottom left opens a popover, which Escape or a touch elsewhere closes', () => {
  mount({ debug: true });
  expect(screen.queryByRole('dialog')).toBeNull();

  open();
  const dialog = screen.getByRole('dialog', { name: 'Debug' });
  expect(dialog.textContent).toContain('Show grid');
  expect(dialog.textContent).toContain('Fullscreen');
  expect(dialog.textContent).toContain('Theme');
  expect(screen.getByRole('button', { name: 'Debug menu' }).getAttribute('aria-expanded')).toBe(
    'true',
  );

  fireEvent.keyDown(document, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).toBeNull();

  open();
  fireEvent.pointerDown(document.body);
  expect(screen.queryByRole('dialog')).toBeNull();

  // A touch inside it does not close it.
  open();
  fireEvent.pointerDown(screen.getByRole('dialog'));
  expect(screen.getByRole('dialog')).toBeTruthy();
});

test('the menu is as big as its button, whatever the page says about divs', () => {
  mount({ debug: true });
  const menu = document.querySelector('[data-debug-menu]') as HTMLElement;
  const style = getComputedStyle(menu);
  // The document shell makes a `body > div` as tall as the window; the menu must not follow it.
  expect([style.position, style.height, style.minHeight]).toEqual(['fixed', '40px', '0px']);
  // Centered on the line the navigation rail's icons are centered on (a 64px rail, a 40px button).
  expect(style.left).toBe('12px');
  expect(style.bottom).toBe('12px');
});

test('the button is over the page and its navigation but under a drawer and its scrim', () => {
  mount({ debug: true });
  const menu = document.querySelector('[data-debug-menu]') as HTMLElement;
  // The theme's `dropdown` (10): above the navigation rail (5), below the drawer's scrim (20) and the drawer (21).
  expect(computed(menu, 'z-index')).toBe('10');
});

test('Show grid draws the grid over the page, and is kept on the device', async () => {
  mount({ debug: true });
  expect(document.querySelector('[data-grid-overlay]')).toBeNull();

  open();
  choose('Show grid', 'On');
  await waitFor(() => expect(document.querySelector('[data-grid-overlay]')).not.toBeNull());
  expect(localStorage.getItem('hashsome:grid')).toBe('on');

  // Kept: a reload shows it again, and turning it off forgets it.
  cleanup();
  mount({ debug: true });
  expect(document.querySelector('[data-grid-overlay]')).not.toBeNull();
  open();
  choose('Show grid', 'Off');
  await waitFor(() => expect(document.querySelector('[data-grid-overlay]')).toBeNull());
  expect(localStorage.getItem('hashsome:grid')).toBeNull();
});

test('the theme row chooses light, dark, system or the sun over the configured theme, kept on the device', async () => {
  mount({ debug: true, theme: 'dark', sun: SUN, sunIs: 'on' });
  expect(bg()).toBe(DARK_BG);

  open();
  choose('Theme', 'Light');
  expect(bg()).toBe(LIGHT_BG);
  expect(localStorage.getItem('hashsome:theme')).toBe('light');

  // The sun is up, so following it is light; with it down it is dark.
  choose('Theme', 'Dark');
  expect(bg()).toBe(DARK_BG);
  choose('Theme', 'Sun');
  await waitFor(() => expect(bg()).toBe(LIGHT_BG));
  expect(localStorage.getItem('hashsome:theme')).toBe('sun');

  // jsdom has no matchMedia: the system's preference reads as light.
  choose('Theme', 'System');
  expect(bg()).toBe(LIGHT_BG);

  // Kept: a reload starts from the choice, not from the configured theme.
  choose('Theme', 'Dark');
  cleanup();
  mount({ debug: true, theme: 'light' });
  expect(bg()).toBe(DARK_BG);
});

test('without the menu on, a theme it kept is ignored', () => {
  localStorage.setItem('hashsome:theme', 'light');
  mount({ debug: false, theme: 'dark' });
  expect(bg()).toBe(DARK_BG);
});

test('the sun choice uses the configured sun entity, and shows which theme is in effect', async () => {
  mount({ debug: true, theme: { sun: SUN }, sunIs: 'off' });
  await waitFor(() => expect(bg()).toBe(DARK_BG));

  open();
  const pressed = [...screen.getByRole('dialog').querySelectorAll('button[aria-pressed="true"]')];
  // Nothing chosen yet: the configured theme (the sun) is the one shown.
  expect(pressed.map((button) => button.textContent)).toContain('Sun');
});

test('the menu has no Sun choice unless the project names a sun entity, and Hashsome never picks one', async () => {
  // Nothing says which entity is the sun: only light, dark and system.
  mount({ debug: true, theme: 'dark' });
  open();
  const chips = () =>
    [...screen.getByRole('dialog').querySelectorAll('button[aria-pressed]')].map(
      (button) => button.textContent,
    );

  expect(chips()).not.toContain('Sun');
  expect(chips()).toContain('System');
  cleanup();

  // A sun schedule names one, and so does the `sun` prop.
  mount({ debug: true, theme: { sun: SUN } });
  open();
  expect(chips()).toContain('Sun');
  cleanup();

  mount({ debug: true, theme: 'dark', sun: SUN });
  open();
  expect(chips()).toContain('Sun');
});

test('a Sun choice kept from a visit that had a sun entity leaves the configured theme as it is when there is none', () => {
  localStorage.setItem('hashsome:theme', 'sun');
  mount({ debug: true, theme: 'dark', sunIs: 'on' });
  // Following the sun would be light (it is up); there is no sun to follow, so it stays dark.
  expect(bg()).toBe(DARK_BG);
});

test('Fullscreen asks the browser for it in the touch itself, and leaves it again', () => {
  const request = vi.fn<(options?: unknown) => Promise<void>>(() => Promise.resolve());
  const exit = vi.fn<() => Promise<void>>(() => Promise.resolve());
  let element: Element | null = null;
  Object.defineProperty(document, 'fullscreenEnabled', { value: true, configurable: true });
  Object.defineProperty(document, 'fullscreenElement', { get: () => element, configurable: true });
  Object.defineProperty(document, 'exitFullscreen', { value: exit, configurable: true });
  Object.defineProperty(document.documentElement, 'requestFullscreen', {
    value: request,
    configurable: true,
  });

  mount({ debug: true });
  open();
  choose('Fullscreen', 'On');
  expect(request).toHaveBeenCalledWith({ navigationUI: 'hide' });
  expect(localStorage.getItem('hashsome:fullscreen')).toBe('on');

  // The browser has gone fullscreen: the menu shows it, and Off leaves it.
  element = document.documentElement;
  act(() => {
    document.dispatchEvent(new Event('fullscreenchange'));
  });

  choose('Fullscreen', 'Off');
  expect(exit).toHaveBeenCalledTimes(1);
  expect(localStorage.getItem('hashsome:fullscreen')).toBeNull();
});

test('after a reload the first touch puts the page back into fullscreen it was in, and only that', () => {
  const request = vi.fn<(options?: unknown) => Promise<void>>(() => Promise.resolve());
  Object.defineProperty(document, 'fullscreenEnabled', { value: true, configurable: true });
  Object.defineProperty(document, 'fullscreenElement', { get: () => null, configurable: true });
  Object.defineProperty(document.documentElement, 'requestFullscreen', {
    value: request,
    configurable: true,
  });

  localStorage.setItem('hashsome:fullscreen', 'on');
  mount({ debug: true });
  expect(request).not.toHaveBeenCalled();
  window.dispatchEvent(new Event('pointerup'));
  window.dispatchEvent(new Event('pointerup'));
  expect(request).toHaveBeenCalledTimes(1);
});

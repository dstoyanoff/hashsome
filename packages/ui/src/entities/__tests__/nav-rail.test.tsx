import { LocalClient, MockIntegration } from '@hashsome/core';
import { act, cleanup, render, screen } from '@testing-library/react';
import { ThemeProvider } from 'e-prim';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

afterEach(cleanup);
import { HashsomeProvider } from '../../provider.tsx';
import { darkTheme } from '../../theme/index.ts';
import { NavDock } from '../nav-dock.tsx';
import { mainPath, NavRail } from '../nav-rail.tsx';

test('nav rail resolves items against base and marks the current page', () => {
  render(
    <ThemeProvider theme={darkTheme}>
      <MemoryRouter initialEntries={['/home/upstairs']}>
        <NavRail
          base="/home"
          items={[
            { to: '', label: 'Downstairs', icon: 'lu:house' },
            { to: 'upstairs', label: 'Upstairs', icon: 'tb:stairs-up' },
          ]}
        />
      </MemoryRouter>
    </ThemeProvider>,
  );

  expect(screen.getByRole('link', { name: 'Upstairs' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Downstairs' }).getAttribute('aria-current')).toBeNull();
  expect(screen.getByRole('link', { name: 'Downstairs' }).getAttribute('href')).toBe('/home');
});

test('an item with attention says so to a screen reader, and the others do not', () => {
  render(
    <ThemeProvider theme={darkTheme}>
      <MemoryRouter>
        <NavRail
          base="/home"
          items={[
            { to: '', label: 'Home', icon: 'lu:house' },
            { to: 'maintenance', label: 'Maintenance', icon: 'lu:wrench', attention: 'urgent' },
          ]}
        />
      </MemoryRouter>
    </ThemeProvider>,
  );

  expect(screen.getByRole('link', { name: 'Maintenance, needs attention' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy();
});

describe('going back to the main page when the display is left alone', () => {
  const MIN = 60_000;
  const items = [
    { to: '', label: 'Home', icon: 'lu:house' as const },
    { to: 'music', label: 'Music', icon: 'lu:music' as const },
  ];

  function Here() {
    return <output>{useLocation().pathname}</output>;
  }

  const show = (
    nav: 'rail' | 'dock',
    { provider, own }: { provider?: number | false; own?: number | false } = {},
  ) =>
    render(
      <HashsomeProvider
        client={new LocalClient([new MockIntegration({})])}
        {...(provider !== undefined ? { idleReturn: provider } : {})}
      >
        <MemoryRouter initialEntries={['/home/music']}>
          <Here />
          {nav === 'rail' ? (
            <NavRail
              base="/home"
              items={items}
              {...(own !== undefined ? { idleReturn: own } : {})}
            />
          ) : (
            <NavDock
              base="/home"
              items={items}
              {...(own !== undefined ? { idleReturn: own } : {})}
            />
          )}
        </MemoryRouter>
      </HashsomeProvider>,
    );

  const where = () => screen.getByRole('status', { hidden: true }).textContent;

  beforeEach(() => vi.useFakeTimers({ now: new Date('2026-06-01T12:00:00Z') }));
  afterEach(() => vi.useRealTimers());

  test.each(['rail', 'dock'] as const)('off unless the app turns it on (%s)', (nav) => {
    show(nav);
    act(() => vi.advanceTimersByTime(60 * MIN));
    expect(where()).toBe('/home/music');
  });

  test.each(['rail', 'dock'] as const)(
    'the provider turns it on for the navigation (%s), and the main page is the item with no path',
    (nav) => {
      show(nav, { provider: 5 * MIN });
      act(() => vi.advanceTimersByTime(4 * MIN));
      expect(where()).toBe('/home/music');
      act(() => vi.advanceTimersByTime(2 * MIN));
      expect(where()).toBe('/home');
    },
  );

  test.each(['rail', 'dock'] as const)(
    'a navigation sets its own time over the provider (%s), or turns it off',
    (nav) => {
      show(nav, { provider: 5 * MIN, own: 10 * MIN });
      act(() => vi.advanceTimersByTime(8 * MIN));
      expect(where()).toBe('/home/music');
      act(() => vi.advanceTimersByTime(4 * MIN));
      expect(where()).toBe('/home');
      cleanup();
      show(nav, { provider: 5 * MIN, own: false });
      act(() => vi.advanceTimersByTime(60 * MIN));
      expect(where()).toBe('/home/music');
    },
  );

  test('the main page is the first item when none has an empty path', () => {
    expect(mainPath(items, '/home')).toBe('/home');
    expect(mainPath([{ to: 'a', label: 'A', icon: 'lu:house' }], '/x')).toBe('/x/a');
    expect(mainPath([], '/x')).toBe('/x');
  });
});

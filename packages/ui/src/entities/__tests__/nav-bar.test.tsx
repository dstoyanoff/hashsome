import { House, Music, Wrench } from 'lucide-react';
import { LocalClient, MockIntegration } from '@hashsome/core';
import { act, cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { NavBar } from '../nav-bar.tsx';

afterEach(cleanup);

const items = [
  { to: '', label: 'Home', icon: <House /> },
  { to: 'music', label: 'Music', icon: <Music /> },
];

const inProvider = (children: React.ReactNode, props: { idleReturn?: number } = {}) => (
  <HashsomeProvider client={new LocalClient([new MockIntegration({})])} theme="dark" {...props}>
    {children}
  </HashsomeProvider>
);

test('the bar resolves items against its base and marks the current page', () => {
  render(
    inProvider(
      <MemoryRouter initialEntries={['/home/music']}>
        <NavBar base="/home" items={items} />
      </MemoryRouter>,
    ),
  );

  expect(screen.getByRole('navigation', { name: 'Pages' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Music' }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
  expect(screen.getByRole('link', { name: 'Home' }).getAttribute('href')).toBe('/home');
});

test('an item with attention says so to a screen reader', () => {
  render(
    inProvider(
      <MemoryRouter>
        <NavBar
          base="/home"
          items={[
            items[0]!,
            { to: 'maintenance', label: 'Maintenance', icon: <Wrench />, attention: 'urgent' },
          ]}
        />
      </MemoryRouter>,
    ),
  );

  expect(screen.getByRole('link', { name: 'Maintenance, needs attention' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Home' })).toBeTruthy();
});

describe('going back to the main page when the display is left alone', () => {
  function Here() {
    return <output>{useLocation().pathname}</output>;
  }

  beforeEach(() => vi.useFakeTimers({ now: new Date('2026-06-01T12:00:00Z') }));
  afterEach(() => vi.useRealTimers());

  test('after the time it is given, and not before', () => {
    render(
      inProvider(
        <MemoryRouter initialEntries={['/home/music']}>
          <Here />
          <NavBar base="/home" items={items} idleReturn={60_000} />
        </MemoryRouter>,
      ),
    );

    act(() => vi.advanceTimersByTime(50_000));
    expect(screen.getByRole('status', { hidden: true }).textContent).toBe('/home/music');
    act(() => vi.advanceTimersByTime(20_000));
    expect(screen.getByRole('status', { hidden: true }).textContent).toBe('/home');
  });
});

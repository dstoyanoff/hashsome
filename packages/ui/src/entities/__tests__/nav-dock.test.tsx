import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, RouterProvider, createMemoryRouter } from 'react-router';
import { afterEach, expect, test } from 'vitest';
import { NavDock } from '../nav-dock.tsx';

test('nav dock resolves items against base and marks the current page', () => {
  render(
    <MemoryRouter initialEntries={['/home']}>
      <NavDock
        base="/home"
        items={[
          { to: '', label: 'Downstairs', icon: 'lu:house' },
          { to: 'upstairs', label: 'Upstairs', icon: 'tb:stairs-up' },
        ]}
      />
    </MemoryRouter>,
  );

  expect(screen.getByRole('link', { name: 'Downstairs' }).getAttribute('aria-current')).toBe(
    'page',
  );

  expect(screen.getByRole('link', { name: 'Upstairs' }).getAttribute('aria-current')).toBeNull();
  expect(screen.getByRole('link', { name: 'Upstairs' }).getAttribute('href')).toBe(
    '/home/upstairs',
  );
});

const openUpstairs = (installed: boolean) => {
  cleanup();
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({ matches: installed && query.includes('standalone') }),
  });

  const router = createMemoryRouter(
    [
      {
        path: '/home/*',
        element: (
          <NavDock
            base="/home"
            items={[
              { to: '', label: 'Downstairs', icon: 'lu:house' },
              { to: 'upstairs', label: 'Upstairs', icon: 'tb:stairs-up' },
            ]}
          />
        ),
      },
    ],
    { initialEntries: ['/home'] },
  );

  render(<RouterProvider router={router} />);

  fireEvent.click(screen.getByRole('link', { name: 'Upstairs' }));
  return router.state.historyAction;
};

afterEach(() => {
  cleanup();
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: undefined });
});

test('in a browser, switching pages adds to the history', () => {
  expect(openUpstairs(false)).toBe('PUSH');
});

test('in an installed app, switching pages replaces the page before, so there is nothing to swipe back to', () => {
  expect(openUpstairs(true)).toBe('REPLACE');
});

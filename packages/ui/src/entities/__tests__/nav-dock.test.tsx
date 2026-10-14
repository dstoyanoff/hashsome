import { render, screen } from '@testing-library/react';
import { ThemeProvider } from 'e-prim';
import { MemoryRouter } from 'react-router';
import { expect, test } from 'vitest';
import { darkTheme } from '../../theme/index.ts';
import { NavDock } from '../nav-dock.tsx';

test('nav dock resolves items against base and marks the current page', () => {
  render(
    <ThemeProvider theme={darkTheme}>
      <MemoryRouter initialEntries={['/home']}>
        <NavDock
          base="/home"
          items={[
            { to: '', label: 'Downstairs', icon: 'lu:house' },
            { to: 'upstairs', label: 'Upstairs', icon: 'tb:stairs-up' },
          ]}
        />
      </MemoryRouter>
    </ThemeProvider>,
  );

  expect(screen.getByRole('link', { name: 'Downstairs' }).getAttribute('aria-current')).toBe(
    'page',
  );

  expect(screen.getByRole('link', { name: 'Upstairs' }).getAttribute('aria-current')).toBeNull();
  expect(screen.getByRole('link', { name: 'Upstairs' }).getAttribute('href')).toBe(
    '/home/upstairs',
  );
});

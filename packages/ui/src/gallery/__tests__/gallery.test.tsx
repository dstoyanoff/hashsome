// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, expect, test } from 'vitest';
import { Gallery } from '../gallery.tsx';

afterEach(cleanup);

test('gallery renders every component', () => {
  render(
    <MemoryRouter initialEntries={['/gallery']}>
      <Gallery />
    </MemoryRouter>,
  );

  for (const name of [
    'Lamp',
    'Bathroom LED',
    'Night Lamp',
    'Living Room Heater',
    'TV Time',
    'Shower',
    'Blank Space',
  ]) {
    expect(screen.getAllByText(name).length).toBeGreaterThan(0);
  }

  // Uniform non-ready states across component types.
  expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(3);
  expect(screen.getAllByText('Not found').length).toBeGreaterThan(0);
  // NavRail, NavDock and NavBar are demoed as separate contained previews here (not as this page's own
  // chrome): the rail and the dock plain and with dots on two pages, the bar plain, so "Home" resolves to one link
  // per preview.
  const homeLinks = screen.getAllByRole('link', { name: 'Home' });
  expect(homeLinks).toHaveLength(5);
  // The pages with a dot say so.
  expect(screen.getAllByRole('link', { name: 'Lights, needs attention' })).toHaveLength(2);
  expect(screen.getAllByRole('link', { name: 'Climate, needs attention' })).toHaveLength(2);
  for (const link of homeLinks) {
    expect(link.getAttribute('aria-current')).toBe('page');
  }
});

test('gallery is documentation — a titled, described section per component', () => {
  render(
    <MemoryRouter initialEntries={['/gallery']}>
      <Gallery />
    </MemoryRouter>,
  );

  expect(screen.getByRole('heading', { name: /component gallery/i, level: 1 })).toBeTruthy();
  for (const title of [
    'Light Tile',
    'Climate Tile',
    'Sensor Readout',
    'Media Player Bar',
    'Navigation',
    'Top Bar',
  ]) {
    expect(screen.getByRole('heading', { name: title, level: 2 })).toBeTruthy();
  }
});

test('each component section lists its props with their descriptions', () => {
  render(
    <MemoryRouter initialEntries={['/gallery']}>
      <Gallery />
    </MemoryRouter>,
  );

  expect(screen.getByText(/Props · LightTile \(\d+\)/)).toBeTruthy();
  expect(
    screen.getByText(
      'A light, as a ref like `ha:light.kitchen_ceiling` or a handle (a custom source).',
    ),
  ).toBeTruthy();

  expect(screen.getByText(/Props · HashsomeProvider/)).toBeTruthy();
});

test('the active media player demo shows artwork, the unavailable one shows the default glyph', () => {
  const { container } = render(
    <MemoryRouter initialEntries={['/gallery']}>
      <Gallery />
    </MemoryRouter>,
  );

  // The bar's own section: other sections (the board, the column, the page) hold players of their own.
  const section = [...container.querySelectorAll('section')].find(
    (candidate) => candidate.querySelector('h2')?.textContent === 'Media Player Bar',
  );

  const bars = [...(section?.querySelectorAll('div[data-status]') ?? [])]
    .filter((bar) => bar.querySelector('button[aria-label="Pause"], button[aria-label="Play"]'))
    .slice(0, 2);

  expect(bars).toHaveLength(2);
  expect(bars[0]!.querySelector('img')?.getAttribute('src')).toBe('/artwork.jpg');
  expect(bars[1]!.querySelector('img')).toBeNull();
  expect(bars[1]!.querySelector('svg')).not.toBeNull();
});

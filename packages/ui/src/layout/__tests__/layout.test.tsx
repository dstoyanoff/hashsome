import { House } from 'lucide-react';
import { LocalClient } from '@hashsome/core';
import { render, screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { NavDock } from '../../entities/nav-dock.tsx';
import { NavRail } from '../../entities/nav-rail.tsx';
import { HashsomeProvider } from '../../provider.tsx';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Page } from '../page.tsx';
import { Grid } from '../grid.tsx';
import { RoomHeader } from '../room-header.tsx';

test('grid sets column count', () => {
  const { container } = renderWithMock(<Grid columns={3}>x</Grid>);
  expect(
    getComputedStyle(container.querySelector('div > div') as HTMLElement).gridTemplateColumns,
  ).toBe('repeat(3, minmax(0, 1fr))');
});

test('room header renders its title, icon and readouts', () => {
  renderWithMock(<RoomHeader title="Porch" icon={<House />} readouts={<span>74 %</span>} />);

  expect(screen.getByRole('heading', { name: 'Porch' })).toBeTruthy();
  expect(screen.getByText('74 %')).toBeTruthy();
});

const pageOf = () => screen.getByRole('main');

test('Page pads by the density, and further for a NavRail or NavDock while it is mounted', () => {
  const view = renderWithMock(
    <Page>
      <NavRail base="/x" items={[{ to: '', label: 'Home', icon: <House /> }]} />
    </Page>,
    {},
    { router: true },
  );

  expect(computed(pageOf(), 'padding-left')).toBe('76px'); // 12 + the rail's 64
  expect(computed(pageOf(), 'padding-bottom')).toBe('12px');
  view.unmount();

  const dock = renderWithMock(
    <Page>
      <NavDock base="/x" items={[{ to: '', label: 'Home', icon: <House /> }]} />
    </Page>,
    {},
    { router: true },
  );

  expect(computed(pageOf(), 'padding-bottom')).toBe('100px'); // 12 + the dock's 88
  expect(computed(pageOf(), 'padding-left')).toBe('12px');
  dock.unmount();

  renderWithMock(<Page>content</Page>);
  expect(computed(pageOf(), 'padding-left')).toBe('12px');
});

test('Page uses the compact density’s spacing', () => {
  const client = new LocalClient([]);
  render(
    <HashsomeProvider client={client} density="compact">
      <Page>content</Page>
    </HashsomeProvider>,
  );

  expect(computed(pageOf(), 'padding-top')).toBe('8px');
});

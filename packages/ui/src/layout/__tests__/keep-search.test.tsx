import { Home, Music } from 'lucide-react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { afterEach, expect, test } from 'vitest';
import { NavRail } from '../../entities/nav-rail.tsx';
import { useKeepSearch } from '../keep-search.ts';

afterEach(cleanup);

function Where() {
  const { pathname, search } = useLocation();
  return <p data-testid="where">{pathname + search}</p>;
}

function Probe({ to }: { to: string }) {
  return <p data-testid="kept">{useKeepSearch()(to)}</p>;
}

test('an address a dashboard goes to keeps the query string the page is under', () => {
  render(
    <MemoryRouter initialEntries={['/room?motion=reduced&grid']}>
      <Probe to="/room/music" />
      <Routes>
        <Route path="*" element={<Probe to="/other?x=1" />} />
      </Routes>
    </MemoryRouter>,
  );
  const [first, second] = screen.getAllByTestId('kept');
  expect(first?.textContent).toBe('/room/music?motion=reduced&grid');
  // One that has a query of its own is left as it is.
  expect(second?.textContent).toBe('/other?x=1');
});

test('with no query there is nothing to keep', () => {
  render(
    <MemoryRouter initialEntries={['/room']}>
      <Probe to="/room/music" />
    </MemoryRouter>,
  );

  expect(screen.getByTestId('kept').textContent).toBe('/room/music');
});

test('the rail’s links go to the other pages with the same query string', () => {
  render(
    <MemoryRouter initialEntries={['/room?motion=reduced']}>
      <NavRail
        base="/room"
        items={[
          { to: '', label: 'One', icon: <Home /> },
          { to: 'two', label: 'Two', icon: <Music /> },
        ]}
        idleReturn={false}
      />
      <Where />
    </MemoryRouter>,
  );

  fireEvent.click(screen.getByLabelText('Two'));
  expect(screen.getByTestId('where').textContent).toBe('/room/two?motion=reduced');

  fireEvent.click(screen.getByLabelText('One'));
  expect(screen.getByTestId('where').textContent).toBe('/room?motion=reduced');
});

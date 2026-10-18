import { House, Music, Sun } from 'lucide-react';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, expect, test } from 'vitest';
import type { NavItem } from '../../entities/nav-rail.tsx';
import { AnimatedOutlet } from '../animated-outlet.tsx';

afterEach(cleanup);

const items: NavItem[] = [
  { to: '', label: 'One', icon: <House /> },
  { to: 'two', label: 'Two', icon: <Music /> },
  { to: 'three', label: 'Three', icon: <Sun /> },
];

function app(withOrder: boolean, start = '/room') {
  return render(
    <MemoryRouter initialEntries={[start]}>
      <Link to="/room">to one</Link>
      <Link to="/room/two">to two</Link>
      <Link to="/room/three">to three</Link>
      <Routes>
        <Route
          path="/room"
          element={<AnimatedOutlet {...(withOrder ? { items, base: '/room' } : {})} />}
        >
          <Route index element={<p>page one</p>} />
          <Route path="two" element={<p>page two</p>} />
          <Route path="three" element={<p>page three</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/** The inline style a page's wrapper has the moment it first appears. */
function firstStyleOf(text: string, container: HTMLElement) {
  let first: { opacity: string; transform: string } | undefined;
  const look = () => {
    const wrapper = screen.queryByText(text)?.parentElement;
    if (wrapper && !first) {
      first = { opacity: wrapper.style.opacity, transform: wrapper.style.transform };
    }
  };

  new MutationObserver(look).observe(container, { childList: true, subtree: true });
  return () => first;
}

test('shows the page for the address, and the next one after a navigation, not both', async () => {
  app(true);
  expect(screen.getByText('page one')).toBeTruthy();

  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());
  await waitFor(() => expect(screen.queryByText('page one')).toBeNull());
});

test('a later page comes in from the right, an earlier one from the left', async () => {
  const { container } = app(true);
  const forward = firstStyleOf('page three', container);
  fireEvent.click(screen.getByText('to three'));
  await waitFor(() => expect(screen.getByText('page three')).toBeTruthy());
  expect(forward()?.opacity).toBe('0');
  expect(forward()?.transform).toContain('24px');

  const back = firstStyleOf('page two', container);
  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());
  expect(back()?.transform).toContain('-24px');
});

test('without an order the pages only fade', async () => {
  const { container } = app(false);
  const first = firstStyleOf('page two', container);
  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());
  expect(first()?.opacity).toBe('0');
  // No sideways shift: either no transform, or none that moves it.
  expect(first()?.transform ?? '').not.toMatch(/-?\d+px/);
});

test('the page fills the space it is given, as it does without the wrapper', () => {
  app(true);
  const wrapper = screen.getByText('page one').parentElement!;
  expect(getComputedStyle(wrapper).display).toBe('flex');
  expect(getComputedStyle(wrapper).flexDirection).toBe('column');
  expect(getComputedStyle(wrapper).flexGrow).toBe('1');
});

test('the first page shows at once, without a transition', () => {
  app(true);
  const wrapper = screen.getByText('page one').parentElement!;
  expect(wrapper.style.opacity === '' || wrapper.style.opacity === '1').toBe(true);
  act(() => undefined);
});

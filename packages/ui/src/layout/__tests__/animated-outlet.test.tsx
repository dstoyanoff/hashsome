import { House, Music, Sun } from 'lucide-react';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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

/** What a page's wrapper says the moment it first appears: whether it enters, and from how far to the side (the CSS does the rest). */
function firstOf(text: string, container: HTMLElement) {
  let first: { enter: string | undefined; side: string } | undefined;
  const look = () => {
    const wrapper = screen.queryByText(text)?.parentElement;
    if (wrapper && !first) {
      first = { enter: wrapper.dataset['enter'], side: wrapper.style.getPropertyValue('--side') };
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
  const forward = firstOf('page three', container);
  fireEvent.click(screen.getByText('to three'));
  await waitFor(() => expect(screen.getByText('page three')).toBeTruthy());
  expect(forward()?.enter).toBe('true');
  expect(forward()?.side).toBe('24px');

  const back = firstOf('page two', container);
  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());
  expect(back()?.side).toBe('-24px');
});

test('without an order the pages only fade', async () => {
  const { container } = app(false);
  const first = firstOf('page two', container);
  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());
  expect(first()?.enter).toBe('true');
  // No sideways shift.
  expect(first()?.side).toBe('0px');
});

test('going back to the page the visit opened on comes in too, as going to any other does', async () => {
  const { container } = app(true);
  fireEvent.click(screen.getByText('to two'));
  await waitFor(() => expect(screen.getByText('page two')).toBeTruthy());

  const back = firstOf('page one', container);
  fireEvent.click(screen.getByText('to one'));
  await waitFor(() => expect(screen.getByText('page one')).toBeTruthy());
  expect(back()?.enter).toBe('true');
  expect(back()?.side).toBe('-24px');
});

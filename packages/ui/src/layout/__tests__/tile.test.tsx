import { LocalClient, mockLight, MockIntegration } from '@hashsome/core';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { computed, renderWithMock, settle } from '../../test-utils.tsx';
import { Tile } from '../tile.tsx';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

test('a short press calls onPress and never opens the drawer', () => {
  const onPress = vi.fn<() => void>();
  renderWithMock(<Tile label="lamp" onPress={onPress} detail={<span>lamp detail</span>} />);
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(200));
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  fireEvent.click(tile);
  expect(onPress).toHaveBeenCalledTimes(1);
  expect(screen.queryByText('lamp detail')).toBeNull();
});

test('holding for 500ms opens the drawer and suppresses the press', () => {
  const onPress = vi.fn<() => void>();
  renderWithMock(<Tile label="lamp" onPress={onPress} detail={<span>lamp detail</span>} />);
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  expect(screen.getByText('lamp detail')).toBeTruthy();
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  fireEvent.click(tile);
  expect(onPress).not.toHaveBeenCalled();
});

test('dragging an adjustable tile cancels a pending hold', () => {
  renderWithMock(
    <Tile label="led" active fill={0.5} onFillChange={() => {}} detail={<span>led detail</span>} />,
  );

  const tile = screen.getByRole('button', { name: 'led' });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 100, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 150, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  expect(screen.queryByText('led detail')).toBeNull();
});

test("an on tile's percentage text is readable regardless of dimmable-ness", () => {
  // Regression 1: an accented (on) tile's secondary/percentage color was always the solid-accent
  // `accentText` (white in both themes), even for a dimmable tile whose card background is reset
  // to the neutral `surface` — white-on-white (or near enough) once `surface` is light instead of
  // near-black. Effectively invisible in light mode.
  const { container } = renderWithMock(
    <Tile label="led" active fill={0.6} secondary="60%" onFillChange={() => {}} />,
  );

  const card = container.querySelector('[data-active="true"]');
  // `data-solid-accent` is the one piece of this that isn't motion-animated (so it's actually
  // observable in jsdom, unlike the `animate`-driven card background/text color — motion doesn't
  // commit those synchronously here). `cardBg` legitimately still differs for a dimmable tile (its
  // own `fill` bar is the "how on" indicator, not a solid-colored card), so this stays `false`.
  expect(card?.getAttribute('data-solid-accent')).toBe('false');
  // `line`, not `accentText` (white) — readable against the tile's neutral `surface` background.
  expect(computed(screen.getByText('60%'), 'color')).toBe('#726C6A');
});

test('dragging an off (inactive) tile shows the live fill bar, not just once it turns on', () => {
  // Regression: `showFill` required `active` (the entity's current, pre-drag on/off state), so
  // dragging an off light up to a brightness showed no moving fill at all until the backend
  // confirmed it turned on — "I don't see the orange color moving with the mouse" when off, even
  // though the percentage and the eventual brightness were both correct.
  const { container } = renderWithMock(
    <Tile label="led" active={false} fill={0} onFillChange={() => {}} />,
  );

  expect(container.querySelector('[data-fill-visible="true"]')).toBeNull();
  const tile = screen.getByRole('button', { name: 'led' });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 20, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 100, pointerId: 1 });
  expect(container.querySelector('[data-fill-visible="true"]')).toBeTruthy();
});

test('after releasing a drag, the live value holds until `fill` catches up — no revert flash', () => {
  // `onFillChange` only requests the change; a real backend confirms it asynchronously (a real
  // round trip, unlike the synchronous mock other tests use), so `fill` and `secondary` stay
  // stale for a beat after release. Regression: the tile used to clear its drag state immediately
  // on pointer-up, so it would flash back to the stale pre-drag value until the real state caught
  // up — or stay reverted if it never did.
  const onFillChange = vi.fn<(fill: number) => void>();
  const client = new LocalClient([new MockIntegration({})]);
  const { rerender } = render(
    <HashsomeProvider client={client}>
      <Tile label="led" active fill={0.3} secondary="30%" onFillChange={onFillChange} />
    </HashsomeProvider>,
  );

  const tile = screen.getByRole('button', { name: 'led' });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 100, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 150, pointerId: 1 });
  fireEvent.pointerUp(tile, { clientX: 150, pointerId: 1 });
  expect(onFillChange).toHaveBeenCalledWith(0.75);
  expect(tile.textContent).toContain('75%');
  expect(tile.textContent).not.toContain('30%');

  // Once the backend confirms and new props flow down, it hands off seamlessly — still 75%.
  rerender(
    <HashsomeProvider client={client}>
      <Tile label="led" active fill={0.75} secondary="75%" onFillChange={onFillChange} />
    </HashsomeProvider>,
  );

  expect(tile.textContent).toContain('75%');
});

test('without a detail prop, the tile never opens a drawer', () => {
  const onPress = vi.fn<() => void>();
  renderWithMock(<Tile label="lamp" onPress={onPress} />);
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  fireEvent.click(tile);
  expect(onPress).toHaveBeenCalledTimes(1);
});

test('history alone (no detail) still opens the drawer, showing each entry', async () => {
  renderWithMock(
    <Tile
      label="lamp"
      history={[
        { id: '1', message: 'turned on', actor: 'Dan', timestamp: new Date().toISOString() },
        { id: '2', message: 'turned off', timestamp: new Date().toISOString() },
      ]}
    />,
  );

  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  await settle();
  expect(screen.getByText('History')).toBeTruthy();
  expect(screen.queryByText('Dan')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /History/ }));
  expect(screen.getByText(/turned on/)).toBeTruthy();
  expect(screen.getByText('Turned off')).toBeTruthy();
  expect(screen.getByText('Dan')).toBeTruthy();
});

test('`trailing` stays positioned so it paints above the fill bar', () => {
  // Regression: a `position: absolute` element (the fill bar, since it was moved to span the
  // whole card rather than stopping at the button) always paints above a `position: static` one
  // in the same stacking context, *regardless of DOM order* — `trailing` had no `position` set, so
  // once the fill bar extended into its space, the fill bar covered it (e.g. a color-capable
  // light's palette button became unreadable at a high `fill`) despite coming later in the markup.
  renderWithMock(
    <Tile
      label="led"
      active
      fill={0.92}
      onFillChange={() => {}}
      trailing={<span>trailing</span>}
    />,
  );

  const trailing = screen.getByText('trailing');
  const wrapper = trailing.parentElement;
  expect(getComputedStyle(wrapper!).position).not.toBe('static');
});

test('while an overlay is open the accent fill is hidden so the overlay reads on a neutral card', () => {
  const { container, rerender } = renderWithMock(
    <Tile label="lamp" active fill={0.6} onFillChange={() => {}} />,
  );

  expect(container.querySelector('[data-fill-visible="true"]')).not.toBeNull();
  rerender(
    <HashsomeProvider client={new LocalClient([new MockIntegration()])}>
      <Tile
        label="lamp"
        active
        fill={0.6}
        onFillChange={() => {}}
        overlay={() => <span>swatches</span>}
      />
    </HashsomeProvider>,
  );

  expect(container.querySelector('[data-fill-visible="false"]')).not.toBeNull();
});

test('closing an overlay does not remount the fill bar, so it never replays its grow-in', () => {
  const { container, rerender } = renderWithMock(
    <Tile label="lamp" active fill={0.6} onFillChange={() => {}} overlay={() => <span>x</span>} />,
  );

  const bar = container.querySelector('[data-active] > span');
  expect(bar).not.toBeNull();
  rerender(
    <HashsomeProvider client={new LocalClient([new MockIntegration()])}>
      <Tile label="lamp" active fill={0.6} onFillChange={() => {}} />
    </HashsomeProvider>,
  );

  expect(container.querySelector('[data-active] > span')).toBe(bar);
});

test('the hold progress bar lives on the card, not inside the button, so it spans the whole card', () => {
  const { container } = renderWithMock(
    <Tile label="lamp" detail={<span>d</span>} trailing={<span>trailing</span>} />,
  );

  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  // The bar: a thin absolutely-positioned span that is a direct child of the card.
  const card = container.querySelector('[data-status]') as HTMLElement;
  const bar = Array.from(card.children).find(
    (child) =>
      child.tagName === 'SPAN' &&
      getComputedStyle(child).position === 'absolute' &&
      getComputedStyle(child).height === '2px',
  );

  expect(bar).toBeDefined();
  expect(tile.contains(bar as Element)).toBe(false);
});

test('a `logbook` entity fills the History section from the backend, only once it is opened, with placeholders until then', async () => {
  const { ha } = renderWithMock(<Tile label="lamp" logbook="ha:lamp" />, {
    lamp: mockLight({ name: 'Lamp', on: true }),
  });

  const asked = vi.spyOn(ha, 'logbook');
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  await settle();
  // The drawer is open and History is folded: nothing has been asked.
  expect(screen.getByText('History')).toBeTruthy();
  expect(asked).not.toHaveBeenCalled();

  fireEvent.click(screen.getByRole('button', { name: /History/ }));
  expect(asked).toHaveBeenCalledWith('lamp', { limit: 6 });
  expect(screen.getByLabelText('Loading history')).toBeTruthy();
  await act(async () => {});
  expect(screen.queryByLabelText('Loading history')).toBeNull();
  expect(screen.getAllByText(/turned (on|off)|became unavailable/).length).toBeGreaterThan(0);
});

test('a `history` prop wins over the backend, and a backend with no activity shows no section', async () => {
  const { ha } = renderWithMock(
    <Tile
      label="lamp"
      logbook="ha:lamp"
      history={[{ id: '1', message: 'turned on', timestamp: new Date().toISOString() }]}
    />,
    { lamp: mockLight({ name: 'Lamp' }) },
  );

  const asked = vi.spyOn(ha, 'logbook');
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  await settle();
  fireEvent.click(screen.getByRole('button', { name: /History/ }));
  expect(asked).not.toHaveBeenCalled();
  expect(screen.getByText('Turned on')).toBeTruthy();
});

test('an entity the backend has no activity for says so when History is opened', async () => {
  const { ha } = renderWithMock(<Tile label="lamp" logbook="ha:lamp" />, {
    lamp: mockLight({ name: 'Lamp' }),
  });

  // The mock invents activity for a light; this is a backend that keeps none.
  (ha as unknown as { logbook?: undefined }).logbook = undefined;
  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  await settle();
  fireEvent.click(screen.getByRole('button', { name: /History/ }));
  await settle();
  expect(screen.getByText('No recent activity')).toBeTruthy();
});

test('every History row has a picture, so the text lines up: initials, a workflow chip for an automation, or a quiet system chip', async () => {
  renderWithMock(
    <Tile
      label="lamp"
      history={[
        { id: '1', message: 'turned on', actor: 'Dan', timestamp: new Date().toISOString() },
        {
          id: '2',
          message: 'turned off',
          actor: 'Bedtime',
          actorKind: 'automation',
          timestamp: new Date().toISOString(),
        },
        {
          id: '3',
          message: 'became unavailable',
          change: 'availability',
          timestamp: new Date().toISOString(),
        },
        { id: '4', message: 'turned on', change: 'on', timestamp: new Date().toISOString() },
        { id: '5', message: 'turned off', change: 'off', timestamp: new Date().toISOString() },
      ]}
    />,
  );

  const tile = screen.getByRole('button', { name: 'lamp' });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  await settle();
  fireEvent.click(screen.getByRole('button', { name: /History/ }));
  await settle();
  expect(screen.getByRole('img', { name: 'Dan' })).toBeTruthy();
  expect(screen.getByRole('img', { name: 'Bedtime' })).toBeTruthy();
  // Rows with no cause: a connection chip, and a switch thrown one way for on and the other for off.
  const [lost, on, off] = screen.getAllByRole('img', { name: 'System' });
  const icon = (el: HTMLElement | undefined) => el!.querySelector('svg')!.innerHTML;
  expect(new Set([icon(lost), icon(on), icon(off)]).size).toBe(3);
});

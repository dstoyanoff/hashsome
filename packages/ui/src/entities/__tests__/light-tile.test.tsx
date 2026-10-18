import { mockLight } from '@hashsome/core';
import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { LightTile } from '../light-tile.tsx';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const lights = {
  plain: mockLight({ name: 'stairs lamp' }),
  dim: mockLight({ name: 'led', on: true, brightness: 0.6 }),
  color: mockLight({
    name: 'night lamp',
    on: true,
    brightness: 1,
    color: { mode: 'color', hue: 30, saturation: 100 },
    capabilities: { color: true },
  }),
  cct: mockLight({
    name: 'desk lamp',
    on: true,
    brightness: 0.78,
    color: { mode: 'temperature', kelvin: 2700 },
    capabilities: { colorTemperature: true, kelvinRange: { min: 2000, max: 6500 } },
  }),
  gone: mockLight({ name: 'porch', availability: 'unavailable' }),
};

test('shows name and state, and toggles on press', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:plain" />, lights);
  // A plain on/off light shows "Off" when off, and no status text at all when on (matches the
  // converged design — an "On" label would be redundant with the tile's own accent color).
  expect(screen.getByRole('button', { name: /^stairs lamp/ }).textContent).toContain('Off');
  fireEvent.click(screen.getByRole('button', { name: /^stairs lamp/ }));
  expect(ha.getEntity('plain')).toMatchObject({ on: true });
  expect(screen.getByRole('button', { name: /^stairs lamp/ }).textContent).not.toContain('Off');
});

test('name prop overrides the friendly name', () => {
  renderWithMock(<LightTile entity="ha:plain" name="hall" />, lights);
  expect(screen.getByRole('button', { name: /^hall/ })).toBeTruthy();
});

test('dimmable lights show brightness and adjust with arrow keys', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  const tile = screen.getByRole('button', { name: /^led/ });
  expect(tile.textContent).toContain('60%');
  fireEvent.keyDown(tile, { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toEqual({
    entityId: 'dim',
    command: 'setBrightness',
    args: { brightness: 0.65 },
  });
});

test('dragging sets brightness from the pointer position and does not toggle', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  const tile = screen.getByRole('button', { name: /^led/ });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 100, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 150, pointerId: 1 });
  fireEvent.pointerUp(tile, { clientX: 150, pointerId: 1 });
  fireEvent.click(tile);
  expect(ha.calls).toHaveLength(1);
  expect(ha.calls[0]).toMatchObject({ command: 'setBrightness', args: { brightness: 0.75 } });
});

test('dragging shows the live percentage before the drag is committed', () => {
  renderWithMock(<LightTile entity="ha:dim" />, lights);
  const tile = screen.getByRole('button', { name: /^led/ });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 100, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 150, pointerId: 1 });
  expect(tile.textContent).toContain('75%');
  fireEvent.pointerUp(tile, { clientX: 150, pointerId: 1 });
});

test('dragging to the far left turns the light off', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  const tile = screen.getByRole('button', { name: /^led/ });
  tile.getBoundingClientRect = () => ({ left: 0, width: 200 }) as DOMRect;
  fireEvent.pointerDown(tile, { clientX: 100, pointerId: 1 });
  fireEvent.pointerMove(tile, { clientX: 0, pointerId: 1 });
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setBrightness', args: { brightness: 0 } });
  expect(ha.getEntity('dim')).toMatchObject({ on: false });
});

test('the palette button opens a swatch overlay in the card and turns into a close button', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:cct" />, lights);
  expect(screen.queryByRole('button', { name: 'Warm white' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  expect(screen.getAllByRole('button', { name: /white|Candle|Daylight/ })).toHaveLength(4);
  fireEvent.click(screen.getByRole('button', { name: 'Warm white' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setColorTemperature', args: { kelvin: 2700 } });
  // Picking a color closes the overlay.
  expect(screen.queryByRole('button', { name: 'Warm white' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  expect(screen.getByRole('button', { name: 'Warm white' }).getAttribute('aria-pressed')).toBe(
    'true',
  );

  fireEvent.click(screen.getByRole('button', { name: 'Close colors' }));
  expect(screen.queryByRole('button', { name: 'Warm white' })).toBeNull();
});

test('the swatch list can be overridden, and swatches a light cannot apply are left out', () => {
  renderWithMock(
    <LightTile
      entity="ha:cct"
      colors={[
        { label: 'Lime', hs: [90, 100] },
        { label: 'Reading', kelvin: 3000 },
      ]}
    />,
    lights,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  expect(screen.getByRole('button', { name: 'Reading' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Lime' })).toBeNull();
});

test('color-only lights apply a white preset as an approximate hs color', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:color" />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Daylight' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setColor' });
  expect(ha.calls.at(-1)?.args).toHaveProperty('hue');
});

test('the custom swatch opens the drawer with the color picker already expanded', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:cct" />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Custom color' }));
  expect(screen.queryByRole('button', { name: 'Close colors' })).toBeNull();
  const temperature = screen.getByRole('slider', { name: 'Color temperature' });
  expect(temperature.getAttribute('aria-valuenow')).toBe('2700');
  fireEvent.keyDown(temperature, { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setColorTemperature', args: { kelvin: 2800 } });
});

test('hue is adjustable from the drawer for color lights', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:color" />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Custom color' }));
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Hue' }), { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toMatchObject({
    command: 'setColor',
    args: { hue: expect.any(Number), saturation: 100 },
  });
});

test('unavailable and missing lights are disabled and say why', () => {
  renderWithMock(
    <>
      <LightTile entity="ha:gone" />
      <LightTile entity="ha:nope" />
    </>,
    lights,
  );

  const gone = screen.getByRole('button', { name: /^porch/ }) as HTMLButtonElement;
  expect(gone.disabled).toBe(true);
  expect(gone.textContent).toContain('Unavailable');
  const missing = screen.getByRole('button', { name: /^nope/ }) as HTMLButtonElement;
  expect(missing.disabled).toBe(true);
  expect(missing.textContent).toContain('Not found');
});

test('holding a light tile opens a drawer with a power switch, without toggling it', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:plain" />, lights);
  const tile = screen.getByRole('button', { name: /^stairs lamp/ });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  expect(screen.getByText('Power')).toBeTruthy();
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  fireEvent.click(tile);
  expect(ha.calls).toHaveLength(0);
});

function openDrawer(name: string) {
  const tile = screen.getByRole('button', { name: new RegExp('^' + name) });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
}

function mockWidth(el: HTMLElement) {
  el.getBoundingClientRect = () =>
    ({ left: 0, width: 200, top: 0, height: 28, right: 200, bottom: 28, x: 0, y: 0 }) as DOMRect;
}

test('a dimmable light’s drawer has a thumbless brightness bar: drag sets it and it sticks', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  openDrawer('led');
  const bar = screen.getByRole('slider', { name: 'Brightness' });
  mockWidth(bar);
  expect(bar.getAttribute('aria-valuenow')).toBe('60');
  fireEvent.pointerDown(bar, { clientX: 50, pointerId: 2 });
  fireEvent.pointerMove(bar, { clientX: 50, pointerId: 2 });
  expect(bar.getAttribute('aria-valuenow')).toBe('25');
  fireEvent.pointerUp(bar, { clientX: 50, pointerId: 2 });
  expect(ha.getEntity('dim')).toMatchObject({ brightness: 0.25 });
  expect(screen.getByRole('slider', { name: 'Brightness' }).getAttribute('aria-valuenow')).toBe(
    '25',
  );
});

test('arrow keys nudge brightness, and dragging to the far left turns the light off', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  openDrawer('led');
  const bar = screen.getByRole('slider', { name: 'Brightness' });
  mockWidth(bar);
  fireEvent.keyDown(bar, { key: 'ArrowRight' });
  expect(ha.getEntity('dim')).toMatchObject({ brightness: 0.65 });
  fireEvent.pointerDown(bar, { clientX: -10, pointerId: 2 });
  fireEvent.pointerUp(bar, { clientX: -10, pointerId: 2 });
  expect(ha.getEntity('dim')).toMatchObject({ on: false });
});

test('an on/off-only light’s drawer has no brightness slider', () => {
  renderWithMock(<LightTile entity="ha:plain" />, lights);
  openDrawer('stairs lamp');
  expect(screen.queryByRole('slider', { name: 'Brightness' })).toBeNull();
});

test('+ / − nudge brightness by 1% per press, and holding steps by 10 until released', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  openDrawer('led');
  const plus = screen.getByRole('button', { name: 'Increase brightness' });
  fireEvent.pointerDown(plus);
  fireEvent.pointerUp(plus);
  fireEvent.click(plus);
  expect(screen.getByRole('slider', { name: 'Brightness' }).getAttribute('aria-valuenow')).toBe(
    '61',
  );

  fireEvent.pointerDown(plus);
  act(() => vi.advanceTimersByTime(400 + 400));
  fireEvent.pointerUp(plus);
  fireEvent.click(plus);
  expect(screen.getByRole('slider', { name: 'Brightness' }).getAttribute('aria-valuenow')).toBe(
    '81',
  );

  expect(ha.getEntity('dim')).toMatchObject({ brightness: 0.81 });
  const minus = screen.getByRole('button', { name: 'Decrease brightness' });
  fireEvent.pointerDown(minus);
  fireEvent.pointerUp(minus);
  fireEvent.click(minus);
  expect(screen.getByRole('slider', { name: 'Brightness' }).getAttribute('aria-valuenow')).toBe(
    '80',
  );
});

test('the palette icon takes the light’s current color while it is on, and is neutral while off or open', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:cct" />, lights);
  const palette = () => screen.getByRole('button', { name: 'Color' });
  const colorOf = (el: HTMLElement) => computed(el, 'color');
  const lit = colorOf(palette());
  expect(lit).toMatch(/^rgb\(/);
  fireEvent.click(palette());
  expect(colorOf(screen.getByRole('button', { name: 'Close colors' }))).not.toBe(lit);
  fireEvent.click(screen.getByRole('button', { name: 'Close colors' }));
  act(() => ha.update('cct', { on: false }));
  expect(colorOf(palette())).not.toBe(lit);
});

test('the drawer offers the browser color picker for color lights, applied once the choice settles', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:color" />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Custom color' }));
  const picker = screen.getByLabelText('Pick any color');
  fireEvent.change(picker, { target: { value: '#00ff00' } });
  fireEvent.change(picker, { target: { value: '#0000ff' } });
  expect(ha.calls).toHaveLength(0);
  act(() => vi.advanceTimersByTime(200));
  expect(ha.calls.at(-1)).toMatchObject({
    command: 'setColor',
    args: { hue: 240, saturation: 100 },
  });
});

test('temperature-only lights have no any-color picker', () => {
  renderWithMock(<LightTile entity="ha:cct" />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Custom color' }));
  expect(screen.queryByLabelText('Pick any color')).toBeNull();
});

test('the drawer’s power control is one icon button with two states', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:dim" />, lights);
  openDrawer('led');
  const button = () => screen.getByRole('button', { name: /^Turn (on|off)$/ });
  expect(button().getAttribute('aria-pressed')).toBe('true');
  expect(button().getAttribute('aria-label')).toBe('Turn off');
  fireEvent.click(button());
  expect(ha.getEntity('dim')).toMatchObject({ on: false });
  expect(button().getAttribute('aria-pressed')).toBe('false');
  expect(button().getAttribute('aria-label')).toBe('Turn on');
  fireEvent.click(button());
  expect(ha.getEntity('dim')).toMatchObject({ on: true });
});

test('drawer={false}: holding the tile opens nothing, and it still toggles with a tap', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:plain" drawer={false} />, lights);
  const tile = screen.getByRole('button', { name: /^stairs lamp/ });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(800));
  expect(screen.queryByText('Power')).toBeNull();
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
  expect(ha.calls).toHaveLength(0);

  fireEvent.click(tile);
  expect(ha.calls.at(-1)).toMatchObject({ command: 'toggle' });
});

test('drawer={false}: a dimmable light still dims by dragging, and the color swatches stay, without a custom color', () => {
  const { ha } = renderWithMock(<LightTile entity="ha:cct" drawer={false} />, lights);
  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  fireEvent.click(screen.getByRole('button', { name: 'Warm white' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setColorTemperature', args: { kelvin: 2700 } });

  fireEvent.click(screen.getByRole('button', { name: 'Color' }));
  expect(screen.getAllByRole('button', { name: /white|Candle|Daylight/ })).toHaveLength(4);
  // The custom color is a card in the drawer, which there is none of.
  expect(screen.queryByRole('button', { name: 'Custom color' })).toBeNull();
});

test('a tile that mounts is drawn as it is: its fill is already at the brightness, not animated from 0', () => {
  renderWithMock(<LightTile entity="ha:dim" />, lights);
  // Before any frame has run: opening a page must not replay the 0 → 60% sweep.
  const card = screen.getByRole('button', { name: /^led/ }).closest('[data-fill]') as HTMLElement;
  const fill = [...card.querySelectorAll('span')].find((span) => span.style.transform !== '');
  expect(fill?.style.transform).toBe('scaleX(0.6)');
});

import { mockClimate, type EntityInput } from '@hashsome/core';
import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { ClimateTile } from '../climate-tile.tsx';

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const heater = (mode: 'off' | 'heat' = 'heat', target = 17): Record<string, EntityInput> => ({
  heater: mockClimate({
    name: 'heater',
    mode,
    targetTemperature: target,
    currentTemperature: 16.4,
    capabilities: { modes: ['off', 'heat'], step: 0.5, range: { min: 5, max: 30 } },
  }),
});

test('shows current and target temperature', () => {
  renderWithMock(<ClimateTile entity="ha:heater" />, heater());
  expect(screen.getByRole('button', { name: /^heater/ }).textContent).toContain('16.4 °C');
  expect(screen.getByText('17 °C')).toBeTruthy();
});

test('the entity’s own unit is shown', () => {
  renderWithMock(<ClimateTile entity="ha:ac" />, {
    ac: mockClimate({ name: 'ac', unit: '°F', targetTemperature: 70, currentTemperature: 72 }),
  });

  expect(screen.getByText('70 °F')).toBeTruthy();
});

test('stepper changes the target by the entity step', () => {
  const { ha } = renderWithMock(<ClimateTile entity="ha:heater" />, heater());
  fireEvent.click(screen.getByRole('button', { name: 'Increase' }));
  expect(ha.getEntity('heater')).toMatchObject({ targetTemperature: 17.5 });
  fireEvent.click(screen.getByRole('button', { name: 'Decrease' }));
  fireEvent.click(screen.getByRole('button', { name: 'Decrease' }));
  expect(ha.getEntity('heater')).toMatchObject({ targetTemperature: 16.5 });
  expect(ha.calls[0]).toEqual({
    entityId: 'heater',
    command: 'setTargetTemperature',
    args: { temperature: 17.5 },
  });
});

test('stepper stops at the entity limits', () => {
  renderWithMock(<ClimateTile entity="ha:heater" />, heater('heat', 30));
  expect((screen.getByRole('button', { name: 'Increase' }) as HTMLButtonElement).disabled).toBe(
    true,
  );
});

test('the mode button opens mode swatches in the card; picking one sets it and closes them', () => {
  const { ha } = renderWithMock(<ClimateTile entity="ha:heater" />, heater('heat'));
  expect(screen.queryByRole('button', { name: 'Cool' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Mode' }));
  expect(screen.getByRole('button', { name: 'Close modes' })).toBeTruthy();
  // The target stepper stays put, and the X sits where the mode icon was (right before it).
  const close = screen.getByRole('button', { name: 'Close modes' });
  const increase = screen.getByRole('button', { name: 'Increase' });
  expect(close.compareDocumentPosition(increase) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Heat' }).getAttribute('aria-pressed')).toBe('true');
  fireEvent.click(screen.getByRole('button', { name: 'Off' }));
  expect(ha.getEntity('heater')).toMatchObject({ mode: 'off' });
  expect(ha.calls.at(-1)).toEqual({
    entityId: 'heater',
    command: 'setMode',
    args: { mode: 'off' },
  });

  expect(screen.queryByRole('button', { name: 'Heat' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Increase' })).toBeTruthy();
});

test('the swatches list every mode the device supports, with friendly names', () => {
  renderWithMock(<ClimateTile entity="ha:ac" />, {
    ac: mockClimate({
      name: 'ac',
      mode: 'cool',
      targetTemperature: 22,
      capabilities: { modes: ['off', 'cool', 'heatCool'] },
    }),
  });

  fireEvent.click(screen.getByRole('button', { name: 'Mode' }));
  expect(screen.getByRole('button', { name: 'Heat/Cool' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Cool' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.queryByRole('button', { name: 'Heat' })).toBeNull();
});

test('unavailable climate shows no controls', () => {
  renderWithMock(<ClimateTile entity="ha:heater" />, {
    heater: mockClimate({ name: 'heater', availability: 'unavailable' }),
  });

  expect(screen.queryByRole('button', { name: 'Increase' })).toBeNull();
  expect(screen.getByRole('button', { name: /^heater/ }).textContent).toContain('Unavailable');
});

function openDrawer(name: string) {
  const tile = screen.getByRole('button', { name: new RegExp('^' + name) });
  fireEvent.pointerDown(tile, { clientX: 0, pointerId: 1 });
  act(() => vi.advanceTimersByTime(500));
  fireEvent.pointerUp(tile, { clientX: 0, pointerId: 1 });
}

const rich: Record<string, EntityInput> = {
  heater: mockClimate({
    name: 'heater',
    mode: 'heat',
    action: 'heating',
    humidity: 48,
    preset: 'home',
    targetTemperature: 17,
    currentTemperature: 16.4,
    capabilities: {
      modes: ['off', 'heat', 'cool'],
      presets: ['home', 'away'],
      step: 0.5,
      range: { min: 5, max: 30 },
    },
  }),
};

test('holding opens a drawer with status, modes, target and presets', () => {
  renderWithMock(<ClimateTile entity="ha:heater" />, rich);
  openDrawer('heater');
  expect(screen.getByText(/Heating · 16.4 °C now · 48% humidity/)).toBeTruthy();
  expect(screen.getByText('Target')).toBeTruthy();
  expect(screen.queryByRole('slider')).toBeNull();
  // Card stepper and drawer stepper both show it.
  expect(screen.getAllByText('17 °C')).toHaveLength(2);
  expect(screen.getByRole('button', { name: 'Away' })).toBeTruthy();
});

test('drawer: modes, presets and the target stepper drive the entity', () => {
  const { ha } = renderWithMock(<ClimateTile entity="ha:heater" />, rich);
  openDrawer('heater');
  fireEvent.click(screen.getByRole('button', { name: 'Cool' }));
  expect(ha.getEntity('heater')).toMatchObject({ mode: 'cool' });
  fireEvent.click(screen.getByRole('button', { name: 'Away' }));
  expect(ha.getEntity('heater')).toMatchObject({ preset: 'away' });
  fireEvent.click(screen.getByRole('button', { name: 'Increase target' }));
  expect(ha.getEntity('heater')).toMatchObject({ targetTemperature: 17.5 });
  fireEvent.click(screen.getByRole('button', { name: 'Decrease target' }));
  fireEvent.click(screen.getByRole('button', { name: 'Decrease target' }));
  expect(ha.getEntity('heater')).toMatchObject({ targetTemperature: 16.5 });
  expect(screen.getAllByText('16.5 °C')).toHaveLength(2);
});

test('drawer: holding a target button steps by several increments', () => {
  const { ha } = renderWithMock(<ClimateTile entity="ha:heater" />, rich);
  openDrawer('heater');
  const plus = screen.getByRole('button', { name: 'Increase target' });
  fireEvent.pointerDown(plus);
  act(() => vi.advanceTimersByTime(400));
  fireEvent.pointerUp(plus);
  fireEvent.click(plus);
  expect(ha.getEntity('heater')).toMatchObject({ targetTemperature: 19 });
});

import { mockSensor } from '@hashsome/core';
import { fireEvent, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { renderWithMock, settle } from '../../test-utils.tsx';
import { SensorReadout } from '../sensor-readout.tsx';

test('formats numeric values with unit', () => {
  renderWithMock(<SensorReadout entity="ha:t" />, {
    t: mockSensor({ value: '18.04', unit: '°C', measurement: 'temperature' }),
  });

  expect(screen.getByText('18 °C')).toBeTruthy();
});

test('non-numeric values are shown as is', () => {
  renderWithMock(<SensorReadout entity="ha:t" />, { t: mockSensor({ value: 'clear' }) });
  expect(screen.getByText('clear')).toBeTruthy();
});

test.each([
  ['unavailable', 'Unavailable'],
  ['unknown', 'Unknown'],
] as const)('%s sensors show %s', (availability, label) => {
  renderWithMock(<SensorReadout entity="ha:t" />, { t: mockSensor({ value: '1', availability }) });
  expect(screen.getByText(label)).toBeTruthy();
});

test('missing sensors say so', () => {
  renderWithMock(<SensorReadout entity="ha:nope" />);
  expect(screen.getByText('Not found')).toBeTruthy();
});

test('an entity of another kind is unsupported', () => {
  renderWithMock(<SensorReadout entity="ha:l" />, {
    l: { kind: 'switch', name: 'S', availability: 'ready', on: true },
  });

  expect(screen.getByText('Unsupported')).toBeTruthy();
});

const humidity = (value: string) => ({
  h: mockSensor({ value, unit: '%', measurement: 'humidity' }),
});

const levelOf = () => document.querySelector('[data-status]')?.getAttribute('data-range') ?? null;

test('humidity is flagged below 30 and above 60 by default, and fine in between', () => {
  const { unmount } = renderWithMock(<SensorReadout entity="ha:h" />, humidity('22'));
  expect(levelOf()).toBe('low');
  unmount();
  const second = renderWithMock(<SensorReadout entity="ha:h" />, humidity('74'));
  expect(levelOf()).toBe('high');
  second.unmount();
  renderWithMock(<SensorReadout entity="ha:h" />, humidity('45'));
  expect(levelOf()).toBe('ok');
});

test('a custom safe range overrides the default, `false` turns it off, temperature has none by default', () => {
  const { unmount } = renderWithMock(
    <SensorReadout entity="ha:h" safeRange={{ min: 40, max: 50 }} />,
    humidity('55'),
  );

  expect(levelOf()).toBe('high');
  unmount();
  const off = renderWithMock(<SensorReadout entity="ha:h" safeRange={false} />, humidity('99'));
  expect(levelOf()).toBeNull();
  off.unmount();
  renderWithMock(<SensorReadout entity="ha:t" />, {
    t: mockSensor({ value: '40', unit: '°C', measurement: 'temperature' }),
  });

  expect(levelOf()).toBeNull();
});

test('an out-of-range reading says why, for assistive tech and on hover', () => {
  renderWithMock(<SensorReadout entity="ha:h" name="Bedroom" />, humidity('22'));
  expect(document.querySelector('[data-status]')?.getAttribute('title')).toBe(
    'Bedroom is too low (safe range 30–60 %)',
  );
});

test('without history a numeric readout is still tappable and opens a drawer with the verdict', () => {
  renderWithMock(<SensorReadout entity="ha:h" />, humidity('74'));
  fireEvent.click(screen.getByRole('button', { name: /74 %/ }));
  expect(screen.getByText('Too high · safe range 30–60 %')).toBeTruthy();
  expect(screen.queryByText('History')).toBeNull();
});

test('drawer={false} and non-numeric or unavailable readings stay inline text', () => {
  const { unmount } = renderWithMock(
    <SensorReadout entity="ha:h" drawer={false} />,
    humidity('45'),
  );

  expect(screen.queryByRole('button')).toBeNull();
  unmount();
  const word = renderWithMock(<SensorReadout entity="ha:w" />, {
    w: mockSensor({ value: 'clear' }),
  });

  expect(screen.queryByRole('button')).toBeNull();
  word.unmount();
  renderWithMock(<SensorReadout entity="ha:w" />, {
    w: mockSensor({ value: '1', availability: 'unavailable' }),
  });

  expect(screen.queryByRole('button')).toBeNull();
});

describe('with history', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 5, 17, 12, 0));
  });

  afterEach(() => vi.useRealTimers());

  const history = [
    { timestamp: new Date(2026, 4, 20, 12).toISOString(), value: 4 },
    { timestamp: new Date(2026, 5, 5, 12).toISOString(), value: 6 },
    { timestamp: new Date(2026, 5, 15, 12).toISOString(), value: 8 },
    { timestamp: new Date(2026, 5, 17, 6).toISOString(), value: 20 },
    { timestamp: new Date(2026, 5, 17, 11).toISOString(), value: 22 },
  ];

  const temp = {
    t: mockSensor({ name: 'Study', value: '21', unit: '°C', measurement: 'temperature' }),
  };

  const tile = (label: string) => screen.getByText(label).parentElement?.textContent;

  test('tapping opens a drawer with min–max for today, this week and this month', async () => {
    renderWithMock(<SensorReadout entity="ha:t" history={history} />, temp);
    fireEvent.click(screen.getByRole('button', { name: /Study: 21 °C/ }));
    await settle();
    expect(tile('Today')).toContain('20 – 22 °C');
    expect(tile('This week')).toContain('8 – 22 °C');
    expect(tile('This month')).toContain('6 – 22 °C');
    expect(screen.queryByText('Last 7 days')).toBeNull();
  });

  test('expanded, it aggregates the rolling windows and a custom date range', async () => {
    renderWithMock(<SensorReadout entity="ha:t" history={history} />, temp);
    fireEvent.click(screen.getByRole('button', { name: /Study: 21 °C/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    await settle();
    expect(tile('Last 7 days')).toContain('8 – 22 °C');
    expect(tile('Last 30 days')).toContain('4 – 22 °C');
    expect(screen.queryByText('Custom range')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Custom' }));
    expect(tile('Custom range')).toContain('8 – 22 °C');

    const toggle = (name: string) => fireEvent.click(screen.getByRole('button', { name }));
    toggle('From');
    toggle('June 1, 2026');
    toggle('From');
    expect(tile('Custom range')).toContain('6 – 22 °C');

    toggle('To');
    toggle('June 10, 2026');
    toggle('To');
    expect(tile('Custom range')).toContain('6 – 6 °C');

    toggle('From');
    toggle('Previous month');
    toggle('May 1, 2026');
    toggle('From');
    expect(tile('Custom range')).toContain('4 – 6 °C');

    // Time matters, not just the day: ending at 09:00 on the 5th excludes that day's 12:00 reading.
    toggle('To');
    toggle('June 5, 2026');
    expect(tile('Custom range')).toContain('4 – 6 °C');
    fireEvent.change(screen.getByLabelText('To hour'), { target: { value: '9' } });
    expect(tile('Custom range')).toContain('4 – 4 °C');
  });

  test('the drawer shows the current value and the safe-range verdict', () => {
    renderWithMock(<SensorReadout entity="ha:h" history={history} />, humidity('74'));
    fireEvent.click(screen.getByRole('button', { name: /74 %/ }));
    expect(screen.getByText('Too high · safe range 30–60 %')).toBeTruthy();
  });
});

describe('battery', () => {
  const open = (battery: Record<string, unknown>) => {
    renderWithMock(<SensorReadout entity="ha:t" battery="ha:b" />, {
      t: mockSensor({ value: '18', unit: '°C', measurement: 'temperature' }),
      b: mockSensor({ value: '85', unit: '%', measurement: 'battery', ...battery }),
    });

    fireEvent.click(screen.getByText('18 °C'));
  };

  test('the drawer shows the battery percentage', () => {
    open({});
    expect(screen.getByText('Battery')).toBeTruthy();
    expect(screen.getByText('85%')).toBeTruthy();
  });

  test('a low battery says so', () => {
    open({ value: '12' });
    expect(screen.getByText('Battery low')).toBeTruthy();
    expect(screen.getByText('12%')).toBeTruthy();
  });

  test('an unavailable battery shows a dash, not a stale number', () => {
    open({ availability: 'unavailable' });
    expect(screen.getByText('—')).toBeTruthy();
  });

  test('without a battery the drawer has no battery line', () => {
    renderWithMock(<SensorReadout entity="ha:t" />, {
      t: mockSensor({ value: '18', unit: '°C', measurement: 'temperature' }),
    });

    fireEvent.click(screen.getByText('18 °C'));
    expect(screen.queryByText('Battery')).toBeNull();
  });
});

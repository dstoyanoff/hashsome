import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { LocalClient } from '@hashsome/core';
import { useState } from 'react';
import { afterEach, expect, test } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { computed } from '../../test-utils.tsx';
import { DateTimeField } from '../date-time-field.tsx';

afterEach(cleanup);

const client = new LocalClient([]);

function Harness({ initial, min, max }: { initial: string; min?: string; max?: string }) {
  const [value, setValue] = useState(initial);
  return (
    <HashsomeProvider client={client}>
      <DateTimeField
        label="When"
        value={value}
        {...(min ? { min } : {})}
        {...(max ? { max } : {})}
        onChange={setValue}
      />
      <output data-testid="value">{value}</output>
    </HashsomeProvider>
  );
}

const value = () => screen.getByTestId('value').textContent;
const open = () => fireEvent.click(screen.getByRole('button', { name: 'When' }));

test('the calendar opens inline, instantly, and closes again', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  expect(screen.queryByRole('group', { name: 'When picker' })).toBeNull();
  open();
  expect(screen.getByRole('group', { name: 'When picker' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'June 17, 2026' }).getAttribute('aria-pressed')).toBe(
    'true',
  );

  open();
  expect(screen.queryByRole('group', { name: 'When picker' })).toBeNull();
});

test('picking a day keeps the time of day; navigating months works', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  fireEvent.click(screen.getByRole('button', { name: 'June 3, 2026' }));
  expect(value()).toBe('2026-06-03T14:30');
  fireEvent.click(screen.getByRole('button', { name: 'Next month' }));
  fireEvent.click(screen.getByRole('button', { name: 'July 9, 2026' }));
  expect(value()).toBe('2026-07-09T14:30');
});

test('hour and minute are editable and clamped to a valid time', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  fireEvent.change(screen.getByLabelText('When hour'), { target: { value: '9' } });
  expect(value()).toBe('2026-06-17T09:30');
  fireEvent.change(screen.getByLabelText('When minute'), { target: { value: '99' } });
  expect(value()).toBe('2026-06-17T09:59');
});

test('days outside min/max are disabled, and a time past a bound is clamped to it', () => {
  render(<Harness initial="2026-06-17T14:30" min="2026-06-10T08:00" max="2026-06-20T18:00" />);
  open();
  expect((screen.getByRole('button', { name: 'June 9, 2026' }) as HTMLButtonElement).disabled).toBe(
    true,
  );

  expect(
    (screen.getByRole('button', { name: 'June 21, 2026' }) as HTMLButtonElement).disabled,
  ).toBe(true);

  expect(
    (screen.getByRole('button', { name: 'June 10, 2026' }) as HTMLButtonElement).disabled,
  ).toBe(false);

  fireEvent.click(screen.getByRole('button', { name: 'June 20, 2026' }));
  fireEvent.change(screen.getByLabelText('When hour'), { target: { value: '23' } });
  expect(value()).toBe('2026-06-20T18:00');
});

test('the popover closes on Escape and on a press outside it, but not on a press inside', () => {
  render(
    <>
      <Harness initial="2026-06-17T14:30" />
      <button type="button">elsewhere</button>
    </>,
  );

  open();
  fireEvent.pointerDown(screen.getByRole('group', { name: 'When picker' }));
  expect(screen.getByRole('group', { name: 'When picker' })).toBeTruthy();
  fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('group', { name: 'When picker' })).toBeNull();
  open();
  fireEvent.pointerDown(screen.getByRole('button', { name: 'elsewhere' }));
  expect(screen.queryByRole('group', { name: 'When picker' })).toBeNull();
});

test('the calendar is positioned, sized and layered from theme tokens, not inline styles', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  const picker = screen.getByRole('group', { name: 'When picker' });
  const style = getComputedStyle(picker);
  expect(style.position).toBe('absolute');
  expect(computed(picker, 'width')).toBe('288px');
  expect(computed(picker, 'z-index')).toBe('30');
  expect(picker.dataset['align']).toMatch(/left|right/);
  // Hangs under the field with one theme space of 8px between them, aligned to one edge.
  expect(computed(picker, 'top')).toBe('calc(100% + 4px * 2)');
});

test('the days are a seven-column grid of round buttons, from its stylesheet', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  const day = screen.getByRole('button', { name: 'June 17, 2026' });
  const grid = day.parentElement as HTMLElement;
  expect(getComputedStyle(grid).display).toBe('grid');
  expect(computed(grid, 'grid-template-columns')).toBe('repeat(7, minmax(0, 1fr))');
  expect(computed(grid, 'gap')).toBe('2px');
  expect(computed(day, 'width')).toBe('32px');
  expect(computed(day, 'height')).toBe('32px');
  expect(computed(day, 'border-radius')).toBe('999px');
  // The weekday headings get their vertical padding from the spacing unit.
  expect(computed(screen.getByText('Mon'), 'padding-top')).toBe('4px');
});

test('the month arrows are 28px round buttons without inline styles', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  const arrow = screen.getByRole('button', { name: 'Previous month' });
  expect(computed(arrow, 'width')).toBe('28px');
  expect(computed(arrow, 'height')).toBe('28px');
  expect(computed(arrow, 'border-radius')).toBe('999px');
});

test('the time boxes get their size, spacing, border, colors and alignment from props, and their font from the theme', () => {
  render(<Harness initial="2026-06-17T14:30" />);
  open();
  const hour = screen.getByRole('spinbutton', { name: 'When hour' });
  expect(computed(hour, 'width')).toBe('44px');
  expect(computed(hour, 'padding-top')).toBe('4px');
  expect(computed(hour, 'padding-left')).toBe('6px');
  const style = getComputedStyle(hour);
  // The theme's border color, 1px wide: the class that says so, and what the theme gives its variable.
  expect(computed(hour, 'border')).toBe('1px solid rgba(255,255,255,0.09)');
  expect(style.textAlign).toBe('center');
  expect(computed(hour, 'background')).toBe('#211E26'); // the dark theme's surfaceRaised
  expect(computed(hour, 'color')).toBe('#F2EFEA');
  expect(style.fontVariantNumeric).toBe('tabular-nums');
  // The font is the app's, from the theme's body typography, with no `font: inherit` needed.
  expect(computed(hour, 'font-size')).toBe('13px');
  expect(computed(hour, 'font-family')).toBe(
    getComputedStyle(document.documentElement).getPropertyValue('--hs-font-family').trim(),
  );
});

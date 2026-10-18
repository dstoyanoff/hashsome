import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { setMotionLevel } from '../../motion/mode.ts';
import { useState } from 'react';
import { renderWithMock } from '../../test-utils.tsx';
import { afterEach, expect, test } from 'vitest';
import { AnimatedNumber, decimalsOf } from '../animated-number.tsx';
import { Tile } from '../tile.tsx';

afterEach(() => {
  cleanup();
  setMotionLevel('full');
});

const shown = () => screen.getByTestId('n').textContent ?? '';

function Reading({ value, duration = 0.25 }: { value: number; duration?: number }) {
  return (
    <span data-testid="n">
      <AnimatedNumber value={value} duration={duration} format={(n) => `${n.toFixed(1)} W`} />
    </span>
  );
}

test('the first value is shown at once', () => {
  render(<Reading value={40.1} />);
  expect(shown()).toBe('40.1 W');
});

test('a new value is moved to: the old one first, never the new one for a frame, then in between, then it', async () => {
  const view = render(<Reading value={10} />);
  view.rerender(<Reading value={20} />);
  // Before anything is painted it is where it was, not already at the end.
  expect(shown()).toBe('10.0 W');

  await waitFor(() => {
    const now = parseFloat(shown());
    expect(now).toBeGreaterThan(10);
    expect(now).toBeLessThan(20);
  });

  await waitFor(() => expect(shown()).toBe('20.0 W'));
});

test('it can go down as well as up, and ends exactly on the value', async () => {
  const view = render(<Reading value={40} duration={0.15} />);
  view.rerender(<Reading value={3.25} duration={0.15} />);
  await waitFor(() => expect(shown()).toBe('3.3 W'));
});

test('a value that arrives while it is moving is moved to from where it is, and the last one wins', async () => {
  const view = render(<Reading value={0} duration={0.3} />);
  view.rerender(<Reading value={100} duration={0.3} />);
  await waitFor(() => expect(parseFloat(shown())).toBeGreaterThan(0));
  view.rerender(<Reading value={50} duration={0.3} />);
  await waitFor(() => expect(shown()).toBe('50.0 W'));
});

test('reduced motion: it jumps', () => {
  setMotionLevel('reduced');
  const view = render(<Reading value={10} />);
  view.rerender(<Reading value={20} />);
  expect(shown()).toBe('20.0 W');
});

test('the same value again, with a new format function, does not move or jump', () => {
  const view = render(<AnimatedNumber value={7} format={(n) => `${n.toFixed(0)} W`} />);
  view.rerender(<AnimatedNumber value={7} format={(n) => `${n.toFixed(0)} W`} />);
  view.rerender(<AnimatedNumber value={7} format={(n) => `${n.toFixed(0)} W`} />);
  expect(view.container.textContent).toBe('7 W');
});

test('without a format it writes as many decimals as the value has, in every step', async () => {
  const view = render(<AnimatedNumber value={3} duration={0.2} />);
  expect(view.container.textContent).toBe('3');
  view.rerender(<AnimatedNumber value={41.5} duration={0.2} />);
  await waitFor(() => expect(view.container.textContent).toBe('41.5'));
  view.rerender(<AnimatedNumber value={40} duration={0.2} />);
  await waitFor(() => expect(view.container.textContent).toBe('40'));
});

test('a value that is not a number is shown, not moved', () => {
  const view = render(<AnimatedNumber value={Number.NaN} format={(n) => String(n)} />);
  view.rerender(<AnimatedNumber value={5} format={(n) => String(n)} />);
  expect(view.container.textContent).toBe('5');
});

test('decimalsOf counts the decimals a number is written with', () => {
  expect(decimalsOf(40)).toBe(0);
  expect(decimalsOf(40.1)).toBe(1);
  expect(decimalsOf(0.125)).toBe(3);
  expect(decimalsOf(Number.NaN)).toBe(0);
});

function Card() {
  const [watts, setWatts] = useState(2.1);
  return (
    <>
      <button onClick={() => setWatts(40.3)}>change</button>
      <Tile
        label="UV lamp"
        secondary={
          <AnimatedNumber value={watts} duration={0.25} format={(n) => `${n.toFixed(1)} W`} />
        }
      />
    </>
  );
}

test("in a tile's second line it moves as well, which is how a dashboard card shows its consumption", async () => {
  renderWithMock(<Card />, {});
  const second = () => screen.getByRole('button', { name: 'UV lamp' }).textContent ?? '';
  expect(second()).toContain('2.1 W');

  fireEvent.click(screen.getByText('change'));
  // Not at the end already.
  expect(second()).toContain('2.1 W');
  await waitFor(() => {
    const now = parseFloat(second().replace(/^UV lamp/, ''));
    expect(now).toBeGreaterThan(2.1);
    expect(now).toBeLessThan(40.3);
  });

  await waitFor(() => expect(second()).toContain('40.3 W'));
});

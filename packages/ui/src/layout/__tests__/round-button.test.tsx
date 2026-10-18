import { fireEvent, screen } from '@testing-library/react';
import { motion } from 'motion/react';
import { expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { RoundButton } from '../round-button.tsx';

const render = (ui: React.ReactElement) => renderWithMock(ui);

test('a round button is a button that is round, at the density’s usual size', () => {
  render(<RoundButton aria-label="Go">+</RoundButton>);
  const button = screen.getByRole('button', { name: 'Go' });
  expect(button.getAttribute('type')).toBe('button');
  expect(button.getAttribute('style')).toBeNull();
  expect(computed(button, 'width')).toBe('44px');
  expect(computed(button, 'height')).toBe('44px');
  expect(computed(button, 'border-radius')).toBe('999px');
  expect(computed(button, 'cursor')).toBe('pointer');
});

test('a size sets both sides from props, and the usual size is not forced over it', () => {
  render(<RoundButton aria-label="Small" size={28} />);
  const button = screen.getByRole('button', { name: 'Small' });
  expect(computed(button, 'width')).toBe('28px');
  expect(computed(button, 'height')).toBe('28px');
});

test('it keeps its size in a row of long text instead of being squeezed', () => {
  render(<RoundButton aria-label="Go" />);
  expect(computed(screen.getByRole('button', { name: 'Go' }), 'flex-shrink')).toBe('0');
});

test('its own class is added to the shared look instead of replacing it', () => {
  render(<RoundButton aria-label="Mine" className="mine" />);
  const button = screen.getByRole('button', { name: 'Mine' });
  expect(button.classList.contains('mine')).toBe(true);
  expect(computed(button, 'border-radius')).toBe('999px');
  expect(computed(button, 'width')).toBe('44px');
});

test('it takes the button’s own props, and a different element through `as`', () => {
  const onClick = vi.fn<() => void>();
  render(
    <RoundButton as={motion.button} aria-label="Animated" disabled onClick={onClick}>
      x
    </RoundButton>,
  );

  const button = screen.getByRole('button', { name: 'Animated' }) as HTMLButtonElement;
  expect(button.disabled).toBe(true);
  fireEvent.click(button);
  expect(onClick).not.toHaveBeenCalled();
  expect(computed(button, 'opacity')).toBe('0.5');
});

test('state comes from data attributes: primary is the accent, active is warm', () => {
  render(
    <>
      <RoundButton aria-label="Primary" data-primary />
      <RoundButton aria-label="Active" data-active />
    </>,
  );

  expect(computed(screen.getByRole('button', { name: 'Primary' }), 'background')).toBe('#B85C38');

  expect(computed(screen.getByRole('button', { name: 'Active' }), 'color')).toBe('#E3B341');
});

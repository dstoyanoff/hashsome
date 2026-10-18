import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { setMotionLevel } from '../../motion/mode.ts';
import { Reveal } from '../reveal.tsx';

afterEach(() => {
  cleanup();
  setMotionLevel('full');
});

test('in full motion what is revealed is wrapped, with its place in the stagger, and takes the room it always did', () => {
  const { container } = render(
    <Reveal index={3}>
      <span>card</span>
    </Reveal>,
  );

  const wrapper = container.firstElementChild as HTMLElement;
  expect(wrapper.contains(container.querySelector('span'))).toBe(true);
  expect(wrapper.style.getPropertyValue('--i')).toBe('3');
});

test('where there is less motion it is not wrapped at all', () => {
  setMotionLevel('light');
  const { container } = render(
    <Reveal>
      <span>card</span>
    </Reveal>,
  );

  expect((container.firstElementChild as HTMLElement).tagName).toBe('SPAN');
});

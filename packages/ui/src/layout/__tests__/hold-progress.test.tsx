import { render } from '@testing-library/react';
import { expect, test } from 'vitest';
import { HoldProgress } from '../hold-progress.tsx';
import { computed } from '../../test-utils.tsx';
import { HOLD_MS } from '../use-hold.ts';

test('the hold line fills by a transform animation over the hold time, not by changing its width', () => {
  const { container } = render(<HoldProgress />);

  const line = container.querySelector('span') as HTMLElement;
  const style = getComputedStyle(line);
  // A full-width line that is scaled from its left end, by a CSS animation the browser runs itself.
  expect(computed(line, 'width')).toBe('100%');
  expect(style.transformOrigin).toContain('left');
  expect(computed(line, 'animation-duration')).toBe(`${HOLD_MS}ms`);
  expect(computed(line, 'animation-timing-function')).toBe('linear');
  // Nothing in JavaScript drives it: no inline transform.
  expect(line.style.transform).toBe('');
});

test('the hold line takes the color it is given, a theme custom property included', () => {
  const { container } = render(<HoldProgress color="var(--hs-accent)" />);

  const line = container.querySelector('span') as HTMLElement;
  expect(line.style.backgroundColor).toBe('var(--hs-accent)');
});

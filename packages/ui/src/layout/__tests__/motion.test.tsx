import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { Motion } from '../motion.tsx';
import { motionLevel, setMotionLevel } from '../../motion/mode.ts';

afterEach(() => {
  cleanup();
  setMotionLevel('full');
  delete document.documentElement.dataset['motion'];
  window.history.replaceState(null, '', '/');
});

test('a dashboard sets how much it moves while it is shown, and the app’s setting comes back after', () => {
  document.documentElement.dataset['motion'] = 'full';
  const view = render(<Motion level="light" />);
  expect(document.documentElement.dataset['motion']).toBe('light');
  expect(motionLevel()).toBe('light');

  view.unmount();
  expect(document.documentElement.dataset['motion']).toBe('full');
  expect(motionLevel()).toBe('full');
});

test('what is under it is drawn in that level from its first render', () => {
  let seen: string | undefined;
  function Probe() {
    seen = motionLevel();
    return null;
  }

  render(
    <Motion level="reduced">
      <Probe />
    </Motion>,
  );

  expect(seen).toBe('reduced');
});

test('the address can still ask for another, for trying one out', () => {
  window.history.replaceState(null, '', '/?motion=full');
  render(<Motion level="light" />);
  expect(document.documentElement.dataset['motion']).toBe('full');
});

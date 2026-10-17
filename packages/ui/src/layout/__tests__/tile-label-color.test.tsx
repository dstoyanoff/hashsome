import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Tile } from '../tile.tsx';

// In its own file, with no fake-timer `beforeEach` (unlike tile.test.tsx) — `motion`'s `animate`
// doesn't commit a style synchronously, and once a fake-timer test elsewhere in a shared file has
// run, calling `vi.useRealTimers()` locally isn't enough to get a reliable settled read (motion
// seems to cache a reference to the patched globals). Real timers, uncontaminated, plus a wait past
// `COLOR_TRANSITION`'s 280ms, is what actually lets the final color be observed here.
test('a solid on/off tile uses the same (neutral, not white) label color as a dimmable one', async () => {
  // Regression: a solid on/off tile (`cardBg` always `accent`) kept white `accentText` for its
  // label, while a dimmable tile's label was fixed to the neutral `text` color for a related
  // contrast bug. At a high `fill`, a dimmable tile's card reads as the exact same solid orange as
  // a non-dimmable one (the fill bar covers it edge to edge), so two different label colors on two
  // identically-colored cards reads as a bug on sight. `text` has decent contrast against `accent`
  // in both themes (unlike white `accentText` against the dimmable tile's neutral `surface`, which
  // is only safe in dark mode), so every accented tile now uses the same, single label color
  // regardless of `fill`.
  const { container } = renderWithMock(<Tile label="lock" active />);
  await new Promise((resolve) => setTimeout(resolve, 400));
  const card = container.querySelector('[data-active="true"]');
  // `#F2EFEA` (darkTheme.palette.text), not `#FFFFFF` (accentText).
  expect(getComputedStyle(card!).color).toBe('rgb(242, 239, 234)');
});

test('the status text goes dark only once the orange fill reaches it; the label never changes', async () => {
  const { container } = renderWithMock(
    <>
      <Tile label="full" secondary="100%" active fill={1} onFillChange={() => {}} />
      <Tile label="low" secondary="10%" active fill={0.1} onFillChange={() => {}} />
    </>,
  );

  await new Promise((resolve) => setTimeout(resolve, 400));
  const color = (text: string) => computed(screen.getByText(text), 'color');
  expect(color('100%')).toBe('#1B1B1F');
  expect(color('10%')).toBe('#726C6A');
  for (const card of container.querySelectorAll('[data-active="true"]')) {
    expect(getComputedStyle(card).color).toBe('rgb(242, 239, 234)');
  }
});

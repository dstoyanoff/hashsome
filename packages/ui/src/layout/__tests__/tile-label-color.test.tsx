import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Tile } from '../tile.tsx';

test('a solid on/off tile uses the same (neutral, not white) label color as a dimmable one', () => {
  // Regression: a solid on/off tile (`cardBg` always `accent`) kept white `accentText` for its
  // label, while a dimmable tile's label was fixed to the neutral `text` color for a related
  // contrast bug. At a high `fill`, a dimmable tile's card reads as the exact same solid orange as
  // a non-dimmable one (the fill bar covers it edge to edge), so two different label colors on two
  // identically-colored cards reads as a bug on sight. `text` has decent contrast against `accent`
  // in both themes (unlike white `accentText` against the dimmable tile's neutral `surface`, which
  // is only safe in dark mode), so every accented tile now uses the same, single label color
  // regardless of `fill`.
  const { container } = renderWithMock(<Tile label="lock" active />);
  const card = container.querySelector('[data-active="true"]');
  // `#F2EFEA` (darkTheme.palette.text), not `#FFFFFF` (accentText).
  expect(computed(card!, 'color')).toBe('#F2EFEA');
});

test('the status text goes dark only once the orange fill reaches it; the label never changes', () => {
  const { container } = renderWithMock(
    <>
      <Tile label="full" secondary="100%" active fill={1} onFillChange={() => {}} />
      <Tile label="low" secondary="10%" active fill={0.1} onFillChange={() => {}} />
    </>,
  );

  const color = (text: string) => computed(screen.getByText(text), 'color');
  expect(color('100%')).toBe('#1B1B1F');
  expect(color('10%')).toBe('#726C6A');
  for (const card of container.querySelectorAll('[data-active="true"]')) {
    expect(computed(card, 'color')).toBe('#F2EFEA');
  }
});

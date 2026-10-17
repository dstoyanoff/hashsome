import { cleanup, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import { afterEach, expect, test } from 'vitest';
import { darkTheme, lightTheme } from '../../theme/index.ts';
import { DENSITY } from '../../theme/density.ts';
import { PALETTE_KEYS, RADIUS_KEYS, SHADOW_KEYS, TYPOGRAPHY_KEYS, Z_INDEX_KEYS } from '../names.ts';
import { Box, Flex, Grid, Typography } from '../prim.tsx';
import { themeCss, themeVariables } from '../variables.ts';

afterEach(cleanup);

const classes = (testId: string) => screen.getByTestId(testId).className.split(' ').toSorted();

test('tokens become classes, joined as strings: nothing is registered while drawing', () => {
  render(
    <Flex
      data-testid="f"
      gap={2}
      p={1.5}
      radius="card"
      background="surfaceRaised"
      color="textMuted"
      align="center"
      justify="space-between"
    />,
  );

  expect(classes('f')).toEqual(
    [
      'hs-ai-center',
      'hs-bg-surface-raised',
      'hs-c-text-muted',
      'hs-d-flex',
      'hs-gap-2',
      'hs-jc-space-between',
      'hs-p-1_5',
      'hs-r-card',
    ].toSorted(),
  );
});

test('centering wins over align and justify, as it always did', () => {
  render(<Flex data-testid="f" center align="flex-end" justify="flex-start" />);
  expect(classes('f')).toEqual(['hs-ai-center', 'hs-d-flex', 'hs-jc-center']);
});

test('what has no class is an inline style, and the system props never reach the element', () => {
  render(
    <Box
      data-testid="b"
      width={44}
      height="100%"
      minWidth={0}
      p={7.25}
      m={[1, 2]}
      background="rgba(0, 0, 0, 0.35)"
      grow={1}
    />,
  );

  const element = screen.getByTestId('b');
  expect(element.style.width).toBe('44px');
  expect(element.style.height).toBe('100%');
  expect(element.getAttribute('style')).toContain('padding: calc(var(--hs-unit) * 7.25)');
  expect(element.getAttribute('style')).toContain(
    'margin: calc(var(--hs-unit) * 1) calc(var(--hs-unit) * 2)',
  );

  expect(element.style.background).toBe('rgba(0, 0, 0, 0.35)');
  expect(classes('b')).toEqual(['hs-grow-1', 'hs-minw-0']);
  expect(element.hasAttribute('width')).toBe(false);
});

test('a className and a style of its own are kept, a ref reaches the element, an onClick makes it clickable', () => {
  const ref = createRef<HTMLButtonElement>();
  render(
    <Box
      as="button"
      ref={ref}
      data-testid="b"
      className="mine"
      style={{ opacity: 0.5 }}
      onClick={() => {}}
    />,
  );

  expect(ref.current).toBe(screen.getByTestId('b'));
  expect(classes('b')).toEqual(['hs-clickable', 'mine']);
  expect(screen.getByTestId('b').style.opacity).toBe('0.5');
});

test('a grid says its columns and gap, and typography its variant and text props', () => {
  render(
    <>
      <Grid data-testid="g" columns={[7]} gap={0.5} />
      <Typography
        data-testid="t"
        variant="bodyStrong"
        align="right"
        noWrap
        uppercase
        textOverflow="ellipsis"
      />
    </>,
  );

  expect(screen.getByTestId('g').style.gridTemplateColumns).toBe('repeat(7, 1fr)');
  expect(classes('g')).toEqual(['hs-d-grid', 'hs-gap-0_5']);
  expect(screen.getByTestId('t').tagName).toBe('SPAN');
  expect(classes('t')).toEqual([
    'hs-nowrap',
    'hs-t-body-strong',
    'hs-ta-right',
    'hs-to-ellipsis',
    'hs-upper',
  ]);
});

test('the names the primitives and stylesheets are made from are the theme’s', () => {
  for (const theme of [darkTheme, lightTheme]) {
    expect(Object.keys(theme.palette).toSorted()).toEqual([...PALETTE_KEYS].toSorted());
    expect(Object.keys(theme.radius).toSorted()).toEqual([...RADIUS_KEYS].toSorted());
    expect(Object.keys(theme.shadow).toSorted()).toEqual([...SHADOW_KEYS].toSorted());
    expect(Object.keys(theme.zIndex).toSorted()).toEqual([...Z_INDEX_KEYS].toSorted());
    expect(Object.keys(theme.typography).toSorted()).toEqual([...TYPOGRAPHY_KEYS].toSorted());
  }
});

test('a theme and a density come to custom properties, a variant starting from what default says', () => {
  const css = themeVariables(darkTheme, DENSITY.compact);
  expect(css).toContain('--hs-bg: #0B0A0D;');
  expect(css).toContain('--hs-radius-card: 25px;');
  expect(css).toContain('--hs-z-modal: 40;');
  expect(css).toContain('--hs-t-label-size: 12px;');
  expect(css).toContain('--hs-t-label-weight: 500;');
  // `secondary` says no weight: it is the parent's.
  expect(css).toContain('--hs-t-secondary-weight: inherit;');
  expect(css).toContain('--hs-space: 8px;');
  expect(css).toContain(`--hs-unit: ${8 / 3}px;`);
  expect(themeCss(lightTheme, DENSITY.comfortable)).toMatch(
    /^:root \{\n[\s\S]*--hs-bg: #F4F1EB;[\s\S]*\}$/,
  );
});

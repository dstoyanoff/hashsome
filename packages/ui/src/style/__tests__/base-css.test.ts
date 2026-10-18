// @vitest-environment node
import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';

const css = readFileSync(new URL('../base.css', import.meta.url), 'utf8');

test('scrollbars are thin, have no track, and use the theme’s colors', () => {
  expect(css).toMatch(/::-webkit-scrollbar \{\s*width: 16px;\s*height: 16px;/);
  expect(css).toMatch(
    /::-webkit-scrollbar-track,\s*::-webkit-scrollbar-corner \{\s*background: transparent;/,
  );

  expect(css).toMatch(
    /::-webkit-scrollbar-thumb \{[^}]*box-shadow: inset 0 0 0 99px var\(--hs-border\)/,
  );

  expect(css).toMatch(
    /::-webkit-scrollbar-thumb:hover \{\s*box-shadow: inset 0 0 0 99px var\(--hs-line\)/,
  );
});

test('the thumb is a few pixels wide with room around it, so it never touches the content', () => {
  const thumb = /::-webkit-scrollbar-thumb \{([^}]*)\}/.exec(css)![1]!;
  const gap = Number.parseFloat(/border: ([\d.]+)px solid transparent/.exec(thumb)![1]!);
  // 16px of scrollbar, 2 × 6.5px of transparent border, a 3px thumb.
  expect(gap).toBe(6.5);
  expect(16 - 2 * gap).toBe(3);
  // The bar is an inset shadow, not a background clipped to the padding box, which a web view may ignore.
  expect(thumb).not.toContain('background-clip');
});

test('browsers that take the standard properties get a thin bar in the theme’s colors', () => {
  expect(css).toMatch(
    /@supports \(scrollbar-width: thin\) \{\s*\* \{\s*scrollbar-width: thin;\s*scrollbar-color: var\(--hs-border\) transparent;/,
  );
});

test('a touch screen has no scrollbars', () => {
  expect(css).toMatch(/@media \(pointer: coarse\) \{\s*\* \{\s*scrollbar-width: none;/);
  expect(css).toMatch(/::-webkit-scrollbar \{\s*display: none;/);
});

test('a device that asks for less motion gets none', () => {
  expect(css).toMatch(
    /@media \(prefers-reduced-motion: reduce\)[^}]*\{[^}]*animation: none !important;/s,
  );
});

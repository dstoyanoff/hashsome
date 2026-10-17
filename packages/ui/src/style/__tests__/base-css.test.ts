// @vitest-environment node
import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';

const css = readFileSync(new URL('../base.css', import.meta.url), 'utf8');

test('scrollbars are thin, have no track, and use the theme’s colors', () => {
  expect(css).toMatch(/::-webkit-scrollbar \{\s*width: 14px;\s*height: 14px;/);
  expect(css).toMatch(
    /::-webkit-scrollbar-track,\s*::-webkit-scrollbar-corner \{\s*background: transparent;/,
  );
  expect(css).toMatch(/::-webkit-scrollbar-thumb \{\s*background: var\(--hs-border\);/);
  expect(css).toMatch(/::-webkit-scrollbar-thumb:hover \{\s*background: var\(--hs-line\);/);
});

test('the thumb is a few pixels wide with room around it, so it never touches the content', () => {
  const thumb = /::-webkit-scrollbar-thumb \{([^}]*)\}/.exec(css)![1]!;
  const gap = Number.parseFloat(/border: ([\d.]+)px solid transparent/.exec(thumb)![1]!);
  // 14px of scrollbar, 2 × 5px of transparent border, a 4px thumb.
  expect(gap).toBe(5);
  expect(14 - 2 * gap).toBe(4);
  expect(thumb).toContain('background-clip: padding-box');
});

test('browsers without ::-webkit-scrollbar get a thin bar from the standard properties', () => {
  const fallback = /@supports not selector\(::-webkit-scrollbar\) \{([\s\S]*?)\n\}/.exec(css)![1]!;
  expect(fallback).toContain('scrollbar-width: thin');
  // Set unconditionally they would turn the webkit rules off in Chrome.
  expect(css.replace(fallback, '')).not.toMatch(/^\*\s*\{[^}]*scrollbar-width/m);
});

test('reduced motion switches off CSS transitions and animations, except the marked ones', () => {
  expect(css).toContain("html[data-motion='reduced'] *:not([data-keep-motion])");
  expect(css).toMatch(/transition: none !important/);
  expect(css).toMatch(/animation: none !important/);
});

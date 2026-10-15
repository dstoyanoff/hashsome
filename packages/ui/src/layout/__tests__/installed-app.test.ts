import { afterEach, expect, test } from 'vitest';
import { isInstalledApp } from '../installed-app.ts';

const media = (matches: boolean) =>
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({ matches: matches && query.includes('standalone') }),
  });

afterEach(() => {
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: undefined });
  Object.defineProperty(navigator, 'standalone', { configurable: true, value: undefined });
});

test('a standalone display mode means it is installed', () => {
  media(true);

  expect(isInstalledApp()).toBe(true);
});

test("Safari's own flag does too", () => {
  Object.defineProperty(navigator, 'standalone', { configurable: true, value: true });

  expect(isInstalledApp()).toBe(true);
});

test('a browser tab is not, with or without media queries', () => {
  media(false);

  expect(isInstalledApp()).toBe(false);
});

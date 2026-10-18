import { expect, test } from 'vitest';
import { DEFAULT_FONT, googleFontHref, isBundledFont } from '../index.ts';

test('the default font comes with the app, any other is a Google Fonts family', () => {
  expect(isBundledFont(DEFAULT_FONT)).toBe(true);
  expect(isBundledFont(' Inter ')).toBe(true);
  expect(isBundledFont('Roboto')).toBe(false);
  expect(googleFontHref('Open Sans')).toContain('family=Open+Sans');
});

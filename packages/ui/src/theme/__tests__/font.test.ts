import { expect, test } from 'vitest';
import { DEFAULT_FONT, fontStack } from '../index.ts';

test('the font is named first, with the system fonts behind it until its files have loaded', () => {
  expect(DEFAULT_FONT).toBe('Inter');
  expect(fontStack('Roboto')).toMatch(/^'Roboto', system-ui/);
});

// @vitest-environment node
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test } from 'vitest';

const SRC = fileURLToPath(new URL('..', import.meta.url));

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      return name === '__tests__' ? [] : sourceFiles(path);
    }

    return /\.(ts|tsx)$/.test(name) ? [path] : [];
  });
}

// Where the typography scale itself is defined/resolved.
const DEFINES_TYPOGRAPHY = new Set([
  'theme/index.ts',
  'theme/tokens.ts',
  'theme/use-typography.ts',
  'style/variables.ts',
]);

test('components size text through `<Typography>` variants, never raw font sizes/weights', () => {
  const offenders: string[] = [];
  for (const file of sourceFiles(SRC)) {
    const name = relative(SRC, file);
    if (DEFINES_TYPOGRAPHY.has(name)) {
      continue;
    }

    const text = readFileSync(file, 'utf8');
    // A literal number/string next to fontSize, or any fontWeight/letterSpacing/font-size.
    if (/fontSize\s*[:=]\s*[{]?\s*[\d'"`]|fontWeight|letterSpacing|font-size/.test(text)) {
      offenders.push(name);
    }
  }

  expect(offenders).toEqual([]);
});

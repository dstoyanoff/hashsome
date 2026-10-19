/**
 * Generates `android/PARITY.md` from `android/parity.json` and the components and hooks
 * `@hashsome/ui` exports (the same extraction as the catalog). Run via `pnpm generate:parity`.
 * CI fails if the output would change.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { formatWithRepoConfig } from './format.ts';
import { renderParity } from './parity-lib.ts';
import { catalogNames } from './ui-catalog-lib.ts';

const ROOT = join(import.meta.dirname, '..');
const OUT_FILE = join(ROOT, 'android', 'PARITY.md');

const parity = JSON.parse(readFileSync(join(ROOT, 'android', 'parity.json'), 'utf8'));
writeFileSync(OUT_FILE, await formatWithRepoConfig(OUT_FILE, renderParity(catalogNames(), parity)));
console.log(`Wrote ${OUT_FILE}`);

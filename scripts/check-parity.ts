/**
 * Fails when `android/parity.json` has drifted from `@hashsome/ui`: an export without an entry, an
 * entry for something no longer exported, or a malformed status. Run via `pnpm check:parity`.
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parityProblems } from './parity-lib.ts';
import { catalogNames } from './ui-catalog-lib.ts';

const file = join(import.meta.dirname, '..', 'android', 'parity.json');
const problems = parityProblems(catalogNames(), JSON.parse(readFileSync(file, 'utf8')));

if (problems.length > 0) {
  console.error(problems.join('\n'));
  console.error(`\n${problems.length} problem(s) in android/parity.json`);
  process.exit(1);
}

console.log('android/parity.json matches @hashsome/ui');

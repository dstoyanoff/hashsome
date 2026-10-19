/**
 * Works out the next version from the conventional commits since the last release, writes it into
 * every file that carries it (the npm packages and `android/gradle.properties`), updates CHANGELOG.md and prints the release notes to `--notes <file>`.
 * Does not touch git or npm: `.github/workflows/release.yml` commits, tags and publishes.
 *
 *   node scripts/release.ts [--level major|minor|patch] [--dry-run] [--notes <file>]
 *
 * Prints `version=<x.y.z>` (or `skip=true` when nothing is releasable) for the workflow to read.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import {
  bump,
  bumpVersionIn,
  currentVersion,
  levelFor,
  notes,
  parse,
  prependChangelog,
  type Commit,
  type Level,
  type Parsed,
} from './release-lib.ts';

const REPO = 'https://github.com/dstoyanoff/hashsome';

const PACKAGE_JSON = /"version": "[^"]+"/;

/** Every file that carries the one shared version: the npm packages (and the root) and the Android libraries'. */
const VERSION_FILES: { file: string; pattern: RegExp }[] = [
  ...[
    'package.json',
    'packages/core/package.json',
    'packages/ui/package.json',
    'packages/runtime/package.json',
    'packages/integrations/home-assistant/package.json',
    'packages/integrations/music-assistant/package.json',
  ].map((file) => ({ file, pattern: PACKAGE_JSON })),
  { file: 'android/gradle.properties', pattern: /^hashsome\.version=.*$/m },
];

const git = (...args: string[]) => execFileSync('git', args, { encoding: 'utf8' }).trim();

const args = process.argv.slice(2);
const flag = (name: string) => {
  const at = args.indexOf(name);
  return at === -1 ? undefined : (args[at + 1] ?? '');
};

const dryRun = args.includes('--dry-run');
const forced = flag('--level') as Level | undefined;
if (forced !== undefined && !['major', 'minor', 'patch'].includes(forced)) {
  throw new Error(`--level must be major, minor or patch, not "${forced}"`);
}

const readJson = (file: string) => JSON.parse(readFileSync(file, 'utf8')) as { version: string };
// The last release is the newest `v*` tag; without one, everything counts.
const tags = git('tag', '--list', 'v*', '--sort=-v:refname').split('\n').filter(Boolean);
const since = tags[0];
const current = currentVersion(since, readJson('package.json').version);
const range = since ? `${since}..HEAD` : 'HEAD';

const SEPARATOR = '\u001e';
const FIELD = '\u001f';
const commits: Commit[] = git('log', range, `--format=%H${FIELD}%s${FIELD}%b${SEPARATOR}`)
  .split(SEPARATOR)
  .map((entry) => entry.trim())
  .filter(Boolean)
  .map((entry) => {
    const [hash, subject, body] = entry.split(FIELD);
    return { hash: hash!, subject: subject ?? '', body: body ?? '' };
  });

const parsed = commits.map(parse).filter((c): c is Parsed => c !== undefined);
const level = forced ?? levelFor(parsed, current);

if (level === undefined) {
  console.log('skip=true');
  console.error(
    `Nothing to release since ${since ?? 'the start'}: no feat, fix, perf or breaking commits.`,
  );

  process.exit(0);
}

const version = bump(current, level);
if (tags.includes(`v${version}`)) {
  throw new Error(
    `v${version} is already tagged: the last release is ${current}, so there is nothing to bump from`,
  );
}

const body = notes(parsed, REPO) || '_Maintenance release._';
console.error(`${since ?? '(first release)'} -> v${version} (${level}), ${commits.length} commits`);
console.log(`version=${version}`);
console.log(`level=${level}`);

const notesFile = flag('--notes');
if (notesFile) {
  writeFileSync(notesFile, `${body}\n`);
}

if (!dryRun) {
  for (const { file, pattern } of VERSION_FILES) {
    if (!existsSync(file)) {
      throw new Error(`${file} is listed in VERSION_FILES but does not exist`);
    }

    writeFileSync(file, bumpVersionIn(readFileSync(file, 'utf8'), pattern, version));
  }

  const date = new Date().toISOString().slice(0, 10);
  let existing = '';
  try {
    existing = readFileSync('CHANGELOG.md', 'utf8');
  } catch {
    // The first release creates it.
  }

  writeFileSync('CHANGELOG.md', prependChangelog(existing, version, date, body));
}

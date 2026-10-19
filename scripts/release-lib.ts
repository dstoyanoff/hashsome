/** The pure parts of a release: reading conventional commits, choosing the bump, writing the changelog. */

export type Level = 'major' | 'minor' | 'patch';

export interface Commit {
  hash: string;
  subject: string;
  body: string;
}

export interface Parsed {
  hash: string;
  type: string;
  scope?: string;
  description: string;
  breaking: boolean;
}

const SUBJECT = /^(\w+)(?:\(([^)]+)\))?(!)?: (.+)$/;

/** Reads `type(scope)!: description`, and a `BREAKING CHANGE:` footer. Anything else is not conventional (undefined). */
export function parse(commit: Commit): Parsed | undefined {
  const match = SUBJECT.exec(commit.subject.trim());
  if (!match) {
    return undefined;
  }

  const [, type, scope, bang, description] = match;
  return {
    hash: commit.hash,
    type: type!.toLowerCase(),
    ...(scope ? { scope } : {}),
    description: description!,
    breaking: bang === '!' || /^BREAKING[ -]CHANGE:/m.test(commit.body),
  };
}

/** Types that are shipped to users; the rest (docs, chore, ci, test, ...) never cause a release on their own. */
const RELEASING = new Set(['feat', 'fix', 'perf']);

/**
 * The level the commits call for, or undefined when none of them changes what is published.
 * Below 1.0 a breaking change is a minor (so 0.x never jumps to 1.0 by accident), a feature a
 * minor and a fix a patch. From 1.0 on, a breaking change is a major.
 */
export function levelFor(commits: Parsed[], current: string): Level | undefined {
  const beta = current.startsWith('0.');
  if (commits.some((c) => c.breaking)) {
    return beta ? 'minor' : 'major';
  }

  if (commits.some((c) => c.type === 'feat')) {
    return 'minor';
  }

  if (commits.some((c) => RELEASING.has(c.type))) {
    return 'patch';
  }

  return undefined;
}

const SEMVER = /^\d+\.\d+\.\d+$/;

/**
 * The version released last. The newest tag says (`v0.1.0`), since a tag is the record of a release
 * and the root package's own version can lag behind one made by hand; with no tag yet, the root
 * package's version is the start.
 */
export function currentVersion(lastTag: string | undefined, packageVersion: string): string {
  const version = lastTag === undefined ? packageVersion : lastTag.replace(/^v/, '');
  if (!SEMVER.test(version)) {
    throw new Error(`"${version}" is not a version like 1.2.3 (from ${lastTag ?? 'package.json'})`);
  }

  return version;
}

export function bump(version: string, level: Level): string {
  const [major, minor, patch] = version.split('.').map(Number) as [number, number, number];
  if (level === 'major') {
    return `${major + 1}.0.0`;
  }

  return level === 'minor' ? `${major}.${minor + 1}.0` : `${major}.${minor}.${patch + 1}`;
}

/**
 * `text` with the first match of `pattern` replaced by the version, in the form the file keeps it:
 * `"version": "x"` for a package.json, `hashsome.version=x` for the Gradle properties. Throws when
 * the pattern matches nothing, so a file that stopped carrying its version fails the release instead
 * of shipping the old one.
 */
export function bumpVersionIn(text: string, pattern: RegExp, version: string): string {
  const match = pattern.exec(text);
  if (!match) {
    throw new Error(`${pattern} matches nothing`);
  }

  const line = match[0].startsWith('"version"')
    ? `"version": "${version}"`
    : `hashsome.version=${version}`;

  return text.replace(pattern, () => line);
}

const SECTIONS: [string, (c: Parsed) => boolean][] = [
  ['⚠ Breaking changes', (c) => c.breaking],
  ['Features', (c) => !c.breaking && c.type === 'feat'],
  ['Fixes', (c) => !c.breaking && c.type === 'fix'],
  ['Performance', (c) => !c.breaking && c.type === 'perf'],
];

/** The notes of one release, as Markdown. Only the user-facing types are listed. */
export function notes(commits: Parsed[], repoUrl: string): string {
  const lines: string[] = [];
  for (const [title, wanted] of SECTIONS) {
    const items = commits.filter(wanted);
    if (items.length === 0) {
      continue;
    }

    lines.push(`### ${title}`, '');
    for (const c of items) {
      const scope = c.scope ? `**${c.scope}:** ` : '';
      lines.push(
        `- ${scope}${c.description} ([${c.hash.slice(0, 7)}](${repoUrl}/commit/${c.hash}))`,
      );
    }

    lines.push('');
  }

  return lines.join('\n').trimEnd();
}

/** Puts a new release section on top of the changelog, under its title. */
export function prependChangelog(
  existing: string,
  version: string,
  date: string,
  body: string,
): string {
  const title = '# Changelog';
  const rest = existing.startsWith(title) ? existing.slice(title.length).trimStart() : existing;
  const section = `## ${version} (${date})\n\n${body}\n`;
  return `${title}\n\n${section}${rest ? `\n${rest}` : ''}`;
}

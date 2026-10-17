/**
 * Generates `packages/ui/icons.json`: every icon from two packs, combined — Lucide (`lu:`)
 * and Tabler's outline set (`tb:`), addressed as `<pack>:<name>` (matching Home Assistant's own
 * `mdi:`-prefixed icon convention). Run via `pnpm generate:icons`. CI fails if the output would
 * change.
 *
 * The bundle only carries the icons a project uses (see `hashsomeIcons`). Deliberately not curated: a dashboard author can use any `lu:`/`tb:` icon name either pack
 * actually ships, with no entry to add here and no script to run — this file only needs
 * regenerating when the pack *versions* in `package.json` change. Pick the best-looking pack per
 * icon case by case (e.g. `lu:lightbulb` but `tb:vacuum-cleaner`); there's no requirement to stay
 * within one pack.
 *
 * Both source packages ship each icon as a small array of SVG child nodes (`[tag, attrs][]`) —
 * Lucide as one real ES module per icon (so this just imports them directly), Tabler as one
 * combined JSON file covering its whole outline set.
 */
import { readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const OUT_FILE = join(import.meta.dirname, '..', 'packages', 'ui', 'icons.json');

const LUCIDE_ICONS_DIR = join(
  import.meta.dirname,
  '..',
  'node_modules',
  'lucide',
  'dist',
  'esm',
  'icons',
);

const TABLER_OUTLINE_FILE = join(
  import.meta.dirname,
  '..',
  'node_modules',
  '@tabler',
  'icons',
  'tabler-nodes-outline.json',
);

type IconNode = readonly [tag: string, attrs: Record<string, string>];

async function loadAllLucide(): Promise<[string, readonly IconNode[]][]> {
  const files = readdirSync(LUCIDE_ICONS_DIR).filter((f) => f.endsWith('.mjs'));
  const entries: [string, readonly IconNode[]][] = [];
  for (const file of files) {
    const name = file.slice(0, -'.mjs'.length);
    const fileUrl = pathToFileURL(join(LUCIDE_ICONS_DIR, file)).href;
    const mod = (await import(fileUrl)) as { default: readonly IconNode[] };
    entries.push([`lu:${name}`, mod.default]);
  }

  return entries;
}

function loadAllTabler(): [string, readonly IconNode[]][] {
  const data = JSON.parse(readFileSync(TABLER_OUTLINE_FILE, 'utf8')) as Record<
    string,
    readonly IconNode[]
  >;

  return Object.entries(data).map(([name, nodes]) => [`tb:${name}`, nodes]);
}

const entries = [...(await loadAllLucide()), ...loadAllTabler()].toSorted(([a], [b]) =>
  a.localeCompare(b),
);

// One icon per line, so a version bump's diff reads as icons added or changed. It is read at build time by
// `@hashsome/ui/vite` (`hashsomeIcons`), which puts only the icons a project's source names into its bundle.
const json = `{\n${entries.map(([id, nodes]) => `  ${JSON.stringify(id)}: ${JSON.stringify(nodes)}`).join(',\n')}\n}\n`;

writeFileSync(OUT_FILE, json);
console.log(`Wrote ${OUT_FILE} (${entries.length} icons)`);

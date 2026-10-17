import { readdirSync, readFileSync, statSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { Plugin, ViteDevServer } from 'vite';

/** The id a bundle imports the icons it uses from; `Icon` does (`src/icon-data.ts`). */
const VIRTUAL = 'virtual:hashsome-icons';
const RESOLVED = `\0${VIRTUAL}`;

/** An icon id wherever it is written: a string, a template, a comment saying a dynamic name is needed. */
const ICON_ID = /(?<![\w:-])(?:lu|tb):[a-z0-9]+(?:-[a-z0-9]+)*/g;

const SOURCE = /\.(?:[cm]?[jt]sx?|mdx)$/;
const SKIP = new Set(['node_modules', 'dist', 'build', '.git', '.react-router', '__tests__']);

export interface HashsomeIconsOptions {
  /** Folders whose source names the icons in use. Default: the project (Vite's root) and every `@hashsome/*` package it has installed. */
  roots?: string[];
}

/** The icon ids a text names. */
export function iconIdsIn(text: string): string[] {
  return text.match(ICON_ID) ?? [];
}

function* sources(dir: string): Generator<string> {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    if (entry.isDirectory()) {
      if (!SKIP.has(entry.name)) {
        yield* sources(join(dir, entry.name));
      }
    } else if (SOURCE.test(entry.name)) {
      yield join(dir, entry.name);
    }
  }
}

/** The installed `@hashsome/*` packages (linked or not), whose own source names icons too. */
function hashsomePackages(root: string): string[] {
  const scope = join(root, 'node_modules', '@hashsome');
  if (!existsSync(scope)) {
    return [];
  }

  return readdirSync(scope)
    .map((name) => {
      const dir = join(scope, name);
      // A linked package's source is where the link leads; the `src` folder is there for a workspace, `dist` when published.
      return statSync(dir).isDirectory() ? dir : '';
    })
    .filter(Boolean);
}

let full: Record<string, unknown> | undefined;
function allIcons(): Record<string, unknown> {
  full ??= JSON.parse(readFileSync(new URL('../../icons.json', import.meta.url), 'utf8')) as Record<
    string,
    unknown
  >;

  return full;
}

/**
 * Puts only the icons a project uses into its bundle. `@hashsome/ui` ships every Lucide and Tabler icon (about 1.7 MB), which a
 * display would otherwise download and parse for the few it draws. This finds the icon ids (`lu:lightbulb`, `tb:vacuum-cleaner`) the
 * project's source and the `@hashsome/*` packages name, anywhere, and `virtual:hashsome-icons` is just those. A name built at
 * run time is not found: write the id somewhere in the source (a comment will do).
 */
export function hashsomeIcons(options: HashsomeIconsOptions = {}): Plugin {
  const used = new Set<string>();
  let roots: string[] = options.roots?.map((root) => resolve(root)) ?? [];
  let server: ViteDevServer | undefined;

  const note = (text: string): boolean => {
    let added = false;
    for (const id of iconIdsIn(text)) {
      if (!used.has(id)) {
        used.add(id);
        added = true;
      }
    }

    return added;
  };

  const inRoots = (file: string) => roots.some((root) => file.startsWith(root));

  return {
    name: 'hashsome:icons',
    enforce: 'pre',
    configResolved(config) {
      if (!options.roots) {
        roots = [config.root, ...hashsomePackages(config.root)].map((root) => resolve(root));
      }
    },
    configureServer(instance) {
      server = instance;
    },
    buildStart() {
      for (const root of roots) {
        for (const file of sources(root)) {
          note(readFileSync(file, 'utf8'));
        }
      }
    },
    resolveId(id) {
      return id === VIRTUAL ? RESOLVED : undefined;
    },
    load(id) {
      if (id !== RESOLVED) {
        return undefined;
      }

      const everything = allIcons();
      const picked = Object.fromEntries(
        [...used]
          .toSorted()
          .flatMap((name) =>
            name in everything ? [[name, JSON.stringify(everything[name])]] : [],
          ),
      );

      return `export const ICON_NODES = ${JSON.stringify(picked)};`;
    },
    // While developing, an icon written into a file after the server started is picked up when that file is next served.
    transform(code, id) {
      const file = id.split('?')[0] ?? id;
      if (server && SOURCE.test(file) && inRoots(file) && note(code)) {
        const module = server.moduleGraph.getModuleById(RESOLVED);
        if (module) {
          server.moduleGraph.invalidateModule(module);
        }
      }

      return undefined;
    },
  };
}

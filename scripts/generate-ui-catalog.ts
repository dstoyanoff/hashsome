/**
 * Generates `.claude/skills/ui-catalog/CATALOG.md`: every exported component and hook in
 * `@hashsome/ui`, with its doc comment and prop/parameter shape, read straight from the source so it
 * cannot drift. Run via `pnpm generate:catalog`. CI fails if the output would change.
 *
 * Parses source text directly (no TS compiler API — this repo's TypeScript is the tsgo preview,
 * which does not expose one) relying on this codebase's consistent style: one prop per line, a
 * single-line `/** ... *\/` doc comment directly above the thing it documents.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { formatWithRepoConfig } from './format.ts';

const UI_SRC = join(import.meta.dirname, '..', 'packages', 'ui', 'src');
const OUT_FILE = join(import.meta.dirname, '..', '.claude', 'skills', 'ui-catalog', 'CATALOG.md');
const PROPS_FILE = join(UI_SRC, 'gallery', 'props-data.ts');

interface PropInfo {
  name: string;
  type: string;
  optional: boolean;
  doc: string;
}

interface InterfaceInfo {
  name: string;
  props: PropInfo[];
}

interface FunctionInfo {
  name: string;
  doc: string;
  signature: string;
}

interface FileCatalog {
  interfaces: InterfaceInfo[];
  functions: FunctionInfo[];
}

interface Section {
  heading: string;
  files: string[];
}

const sections: Section[] = [
  {
    heading: 'Layout',
    files: [
      'layout/grid.tsx',
      'layout/animated-number.tsx',
      'layout/board.tsx',
      'layout/top-row.tsx',
      'layout/page.tsx',
      'layout/animated-outlet.tsx',
      'layout/motion.tsx',
      'layout/room-header.tsx',
      'layout/tile.tsx',
      'layout/task-tile.tsx',
      'layout/confirm-dialog.tsx',
      'layout/energy-chart.tsx',
      'layout/history-section.tsx',
      'icon.tsx',
    ],
  },
  {
    heading: 'Entity components',
    files: [
      'entities/light-tile.tsx',
      'entities/climate-tile.tsx',
      'entities/sensor-readout.tsx',
      'entities/action-button.tsx',
      'entities/scene-button.tsx',
      'entities/media-player-bar.tsx',
      'entities/media-player-column.tsx',
      'entities/media-player-full.tsx',
      'entities/media-browser.tsx',
      'entities/media-queue.tsx',
      'entities/media-speakers.tsx',
      'entities/nav-rail.tsx',
      'entities/nav-dock.tsx',
      'entities/nav-bar.tsx',
      'entities/top-bar.tsx',
      'entities/weather-forecast.tsx',
    ],
  },
  {
    heading: 'Hooks and provider',
    files: ['provider.tsx', 'hooks.ts', 'entity-handle.ts'],
  },
];

/** Finds the index just after the `{` matching the one at `openIndex`. */
function matchBrace(text: string, openIndex: number): number {
  let depth = 1;
  for (let i = openIndex + 1; i < text.length; i++) {
    if (text[i] === '{') {
      depth++;
    } else if (text[i] === '}') {
      depth--;
      if (depth === 0) {
        return i;
      }
    }
  }

  throw new Error(`Unmatched '{' at index ${openIndex}`);
}

/** Turns the inside of a `/** ... *\/` block into one line: leading `*`s dropped, lines joined. */
function cleanDoc(raw: string): string {
  return raw
    .split('\n')
    .map((line) => line.replace(/^\s*\*?\s?/, '').trim())
    .filter(Boolean)
    .join(' ')
    .trim();
}

/** The doc comment (single- or multi-line) directly above `beforeIndex`, if any. */
function docCommentBefore(text: string, beforeIndex: number): string {
  const before = text.slice(0, beforeIndex);
  const end = before.lastIndexOf('*/');
  if (end === -1 || before.slice(end + 2).trim() !== '') {
    return '';
  }

  const start = before.lastIndexOf('/**', end);
  return start === -1 ? '' : cleanDoc(before.slice(start + 3, end));
}

/** Splits an interface body into member texts on top-level `;` (ignoring `;` nested in `{}`/`()`/`[]`,
 * e.g. an inline `{ width: number; height: number }` prop type). Each slice keeps any doc comment
 * directly above its member.
 *
 * Deliberately does NOT track `<`/`>` depth: a lone `>` also closes an arrow function type
 * (`() => void`), which isn't a generic close, so treating it as one desyncs the depth count and
 * corrupts every member after the first arrow-typed prop. None of this codebase's prop types have
 * a `;` inside a generic's `<...>`, so `<`/`>` don't need tracking for this to be correct here. */
function splitMembers(body: string): string[] {
  const members: string[] = [];
  let depth = 0;
  let inComment = false;
  let start = 0;
  for (let i = 0; i < body.length; i++) {
    if (inComment) {
      if (body.startsWith('*/', i)) {
        inComment = false;
      }

      continue;
    }

    if (body.startsWith('/*', i)) {
      inComment = true;
      continue;
    }

    const ch = body[i];
    if (ch === '{' || ch === '(' || ch === '[') {
      depth++;
    } else if (ch === '}' || ch === ')' || ch === ']') {
      depth--;
    } else if (ch === ';' && depth === 0) {
      members.push(body.slice(start, i));
      start = i + 1;
    }
  }

  return members;
}

function parseInterfaces(text: string): InterfaceInfo[] {
  const interfaces: InterfaceInfo[] = [];
  const re = /export interface (\w+) \{/g;
  const memberRe = /^(?:\/\*\*([\s\S]*?)\*\/\s*)?(\w+)(\??):\s*([\s\S]+)$/;

  for (const match of text.matchAll(re)) {
    const openIndex = match.index + match[0].length - 1;
    const closeIndex = matchBrace(text, openIndex);
    const body = text.slice(openIndex + 1, closeIndex);

    const props: PropInfo[] = [];
    for (const member of splitMembers(body)) {
      const propMatch = memberRe.exec(member.trim());
      if (!propMatch) {
        continue;
      }

      props.push({
        doc: cleanDoc(propMatch[1] ?? ''),
        name: propMatch[2]!,
        optional: propMatch[3] === '?',
        // A prop's type can itself be a multi-line inline object (e.g. `ActionButtonProps.action`);
        // strip its doc comments and collapse it to one line so it fits in a table cell.
        type: propMatch[4]!
          .replace(/\/\*\*[\s\S]*?\*\//g, '')
          .replace(/\s+/g, ' ')
          .trim(),
      });
    }

    interfaces.push({ name: match[1]!, props });
  }

  return interfaces;
}

function parseFunctions(text: string): FunctionInfo[] {
  const functions: FunctionInfo[] = [];
  const re = /export function (\w+)\(/g;
  for (const match of text.matchAll(re)) {
    const parenOpen = match.index + match[0].length - 1;
    let depth = 1;
    let i = parenOpen + 1;
    for (; i < text.length; i++) {
      if (text[i] === '(') {
        depth++;
      } else if (text[i] === ')') {
        depth--;
        if (depth === 0) {
          break;
        }
      }
    }

    const params = text
      .slice(parenOpen + 1, i)
      .replace(/\s+/g, ' ')
      .trim();

    const braceOpen = text.indexOf('{', i);
    const returnType = text
      .slice(i + 1, braceOpen)
      .replace(/^\s*:\s*/, '')
      .replace(/\s+/g, ' ')
      .trim();

    functions.push({
      name: match[1]!,
      doc: docCommentBefore(text, match.index),
      signature: `(${params})${returnType ? `: ${returnType}` : ''}`,
    });
  }

  return functions;
}

function parseFile(relativePath: string): FileCatalog {
  const text = readFileSync(join(UI_SRC, relativePath), 'utf8');
  return { interfaces: parseInterfaces(text), functions: parseFunctions(text) };
}

/** Escapes `|` so a value (e.g. a union type like `'a' | 'b'`) can't be mistaken for a markdown
 * table column separator. */
const cell = (value: string) => value.replace(/\|/g, '\\|');

function renderFile(catalog: FileCatalog): string {
  const parts: string[] = [];
  for (const fn of catalog.functions) {
    const props = catalog.interfaces.find((i) => i.name === `${fn.name}Props`);
    parts.push(`### \`${fn.name}\``);
    if (fn.doc) {
      parts.push(fn.doc);
    }

    if (props && props.props.length > 0) {
      parts.push('| Prop | Type | Required | |', '|---|---|---|---|');
      for (const prop of props.props) {
        parts.push(
          `| \`${prop.name}\` | \`${cell(prop.type)}\` | ${prop.optional ? 'no' : 'yes'} | ${cell(prop.doc)} |`,
        );
      }
    } else {
      parts.push(`\`\`\`ts\nfunction ${fn.name}${fn.signature}\n\`\`\``);
    }

    parts.push('');
  }

  return parts.join('\n');
}

const lines: string[] = [
  '# @hashsome/ui component catalog',
  '',
  '_Generated by `pnpm generate:catalog` from `packages/ui/src`. Do not edit by hand — edit the ' +
    "source doc comments and prop types, then regenerate. CI fails if this file doesn't match._",
  '',
  "Import everything from `@hashsome/ui` (e.g. `import { LightTile, RoomHeader } from '@hashsome/ui';`). " +
    "Icons are plain strings, no import needed — `'lu:lightbulb'` (Lucide) or `'tb:vacuum-cleaner'` " +
    '(Tabler outline), prefix:name. Browse names at lucide.dev/icons and tabler.io/icons.',
  '',
];

/** Every documented component, for the gallery's props tables (`gallery/props-data.ts`). */
const componentProps: Record<string, { doc: string; props: PropInfo[] }> = {};

for (const section of sections) {
  lines.push(`## ${section.heading}`, '');
  for (const file of section.files) {
    const catalog = parseFile(file);
    lines.push(renderFile(catalog));
    for (const fn of catalog.functions) {
      if (!/^[A-Z]/.test(fn.name)) {
        continue;
      }

      const props = catalog.interfaces.find((i) => i.name === `${fn.name}Props`);
      componentProps[fn.name] = { doc: fn.doc, props: props?.props ?? [] };
    }
  }
}

const raw =
  lines
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd() + '\n';

// Format with this repo's own oxfmt config so the output always matches `pnpm format:check`
// (e.g. markdown table column padding) — generating already-formatted output, rather than hoping
// a hand-rolled renderer happens to match, is what keeps this file from drifting.
const formatted = await formatWithRepoConfig(OUT_FILE, raw);

writeFileSync(OUT_FILE, formatted);
console.log(`Wrote ${OUT_FILE}`);

const propsSource =
  "// Generated by `pnpm generate:catalog` from the components' own prop interfaces and doc comments.\n" +
  "// Do not edit by hand. The gallery renders this as each component's props table.\n\n" +
  'export interface DocumentedProp {\n  name: string;\n  type: string;\n  optional: boolean;\n  doc: string;\n}\n\n' +
  'export const COMPONENT_PROPS: Record<string, { doc: string; props: DocumentedProp[] }> = ' +
  JSON.stringify(componentProps, null, 2) +
  ';\n';

writeFileSync(PROPS_FILE, await formatWithRepoConfig(PROPS_FILE, propsSource));

console.log(`Wrote ${PROPS_FILE}`);

/** The extraction behind `generate-ui-catalog.ts` and the parity scripts: reads the exported components and hooks of `@hashsome/ui` straight from the source. */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const UI_SRC = join(import.meta.dirname, '..', 'packages', 'ui', 'src');
export const PROPS_FILE = join(UI_SRC, 'gallery', 'props-data.ts');

export interface PropInfo {
  name: string;
  type: string;
  optional: boolean;
  doc: string;
}

export interface InterfaceInfo {
  name: string;
  props: PropInfo[];
}

export interface FunctionInfo {
  name: string;
  doc: string;
  signature: string;
}

export interface FileCatalog {
  interfaces: InterfaceInfo[];
  functions: FunctionInfo[];
}

export interface Section {
  heading: string;
  files: string[];
}

export const sections: Section[] = [
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

export function parseFile(relativePath: string): FileCatalog {
  const text = readFileSync(join(UI_SRC, relativePath), 'utf8');
  return { interfaces: parseInterfaces(text), functions: parseFunctions(text) };
}

/** Escapes `|` so a value (e.g. a union type like `'a' | 'b'`) can't be mistaken for a markdown
 * table column separator. */
const cell = (value: string) => value.replace(/\|/g, '\\|');

export function renderFile(catalog: FileCatalog): string {
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

export interface CatalogName {
  name: string;
  kind: 'component' | 'hook';

  /** The catalog section it sits in. */
  group: string;
}

/** Every component (capitalised function) and hook (`use…`) the catalog lists, in catalog order. Other exported helpers are not part of the public surface the parity table tracks. */
export function catalogNames(): CatalogName[] {
  return sections.flatMap((section) =>
    section.files.flatMap((file) =>
      parseFile(file).functions.flatMap((fn): CatalogName[] =>
        /^[A-Z]/.test(fn.name)
          ? [{ name: fn.name, kind: 'component', group: section.heading }]
          : /^use[A-Z]/.test(fn.name)
            ? [{ name: fn.name, kind: 'hook', group: section.heading }]
            : [],
      ),
    ),
  );
}

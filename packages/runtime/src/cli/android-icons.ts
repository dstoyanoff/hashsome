import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { parseFlags, readGradleValue } from './android.ts';

const USAGE = `Usage: hashsome android icons <pack>... [options]

Converts an icon pack's SVGs to Android vector drawables (24dp, white strokes so they tint) and
writes a Kotlin object per pack, so a dashboard passes \`Lucide.bath\` to a component's \`icon\`.

Packs (resolved from your project's node_modules; install the package first):
  lucide    lucide-static  (icons/*.svg)
  tabler    @tabler/icons  (icons/outline/*.svg, and icons/filled/*.svg as <name>_filled)

Options:
  --dir <folder>       Read the SVGs from this folder instead (one pack; its name is the object's)
  --out <dir>          Where the drawables go (default: android/app/src/main/res/drawable)
  --kotlin <file>      Where the Kotlin object goes (default: android/app/src/main/kotlin/<package>/icons/<Pack>.kt;
                       with several packs, a folder: <file> is then the folder)
  --package <name>     The app's package (default: \`namespace\` in android/app/build.gradle.kts).
                       The object is declared in <name>.icons and imports <name>.R
  --strict             Exit 1 if any icon was skipped (an SVG feature the converter does not support:
                       transform, style, opacity, non-round caps/joins, other elements)
  --dry-run            Show what would be written, change nothing

Examples:
  hashsome android icons lucide tabler
  hashsome android icons mine --dir ./assets/icons
`;

const WHITE = '#FFFFFFFF';

/** Packs this command knows how to find in node_modules: the package, and the SVG folders in it (`suffix` is added to the icon's name). */
export const PACKS: Record<string, { pkg: string; folders: { path: string; suffix: string }[] }> = {
  lucide: { pkg: 'lucide-static', folders: [{ path: 'icons', suffix: '' }] },
  tabler: {
    pkg: '@tabler/icons',
    folders: [
      { path: 'icons/outline', suffix: '' },
      { path: 'icons/filled', suffix: '_filled' },
    ],
  },
};

// --- Naming ---

/** A name that is safe as an Android resource: lower-case letters, digits and underscores. */
export function sanitizeName(name: string): string {
  return name
    .toLowerCase()
    .replace(/[^a-z0-9_]+/g, '_')
    .replace(/_+/g, '_')
    .replace(/^_|_$/g, '');
}

const KOTLIN_KEYWORDS = new Set([
  'as',
  'break',
  'class',
  'continue',
  'do',
  'else',
  'false',
  'for',
  'fun',
  'if',
  'in',
  'interface',
  'is',
  'null',
  'object',
  'package',
  'return',
  'super',
  'this',
  'throw',
  'true',
  'try',
  'typealias',
  'typeof',
  'val',
  'var',
  'when',
  'while',
]);

/** `arrow_down_left` -> `arrowDownLeft`, as a Kotlin property name (backticked when it is a keyword, `_`-prefixed when it starts with a digit). */
export function camelCase(name: string): string {
  const [first = '', ...rest] = sanitizeName(name).split('_');
  const camel = first + rest.map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join('');
  const safe = /^[0-9]/.test(camel) ? `_${camel}` : camel;
  return KOTLIN_KEYWORDS.has(safe) ? `\`${safe}\`` : safe;
}

/** `lucide` -> `Lucide`, `my-icons` -> `MyIcons`: the Kotlin object (and file) for a pack. */
export function packObjectName(pack: string): string {
  return sanitizeName(pack)
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
}

// --- SVG -> vector drawable ---

interface Style {
  fill?: string | undefined;
  stroke?: string | undefined;
  strokeWidth?: string | undefined;
  fillRule?: string | undefined;
}

interface Shape {
  tag: string;
  attrs: Record<string, string>;
  style: Style;
}

export interface ParsedSvg {
  viewBox: [number, number, number, number];
  shapes: Shape[];
}

const SHAPES = new Set(['path', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'rect']);
const IGNORED = new Set(['svg', 'g', 'title', 'desc']);

function attributes(text: string): Record<string, string> {
  const attrs: Record<string, string> = {};
  for (const match of text.matchAll(/([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) {
    attrs[match[1]!] = match[2] ?? match[3] ?? '';
  }

  return attrs;
}

function styleOf(attrs: Record<string, string>, inherited: Style): Style {
  return {
    fill: attrs.fill ?? inherited.fill,
    stroke: attrs.stroke ?? inherited.stroke,
    strokeWidth: attrs['stroke-width'] ?? inherited.strokeWidth,
    fillRule: attrs['fill-rule'] ?? inherited.fillRule,
  };
}

/** Attributes that would change how the icon looks and that the converter does not apply: better to skip the icon than to draw it wrong. */
const UNSUPPORTED = ['transform', 'style', 'opacity', 'fill-opacity', 'stroke-opacity'];

function assertSupported(attrs: Record<string, string>): void {
  for (const name of UNSUPPORTED) {
    if (name in attrs && !(name.endsWith('opacity') && attrs[name]?.trim() === '1')) {
      throw new Error(`unsupported attribute ${name}`);
    }
  }

  // Strokes are always drawn round, as the drawable's one look.
  for (const [name, round] of [
    ['stroke-linecap', 'round'],
    ['stroke-linejoin', 'round'],
  ] as const) {
    if (name in attrs && attrs[name]!.trim() !== round) {
      throw new Error(`unsupported attribute ${name}="${attrs[name]}" (only round)`);
    }
  }
}

/** Reads the viewBox and the drawable shapes of an SVG, with fill and stroke inherited down `<g>`s. Throws on an element it cannot convert. */
export function parseSvg(text: string): ParsedSvg {
  const body = text.replace(/<!--[\s\S]*?-->/g, '').replace(/<\?[\s\S]*?\?>/g, '');
  const stack: Style[] = [];
  const shapes: Shape[] = [];
  let viewBox: ParsedSvg['viewBox'] | undefined;

  for (const match of body.matchAll(/<(\/?)([A-Za-z][\w:-]*)([^>]*?)(\/?)>/g)) {
    const [, closing, tag = '', rest = '', selfClosing] = match;
    if (closing) {
      if (tag === 'svg' || tag === 'g') {
        stack.pop();
      }

      continue;
    }

    const attrs = attributes(rest);
    assertSupported(attrs);
    const style = styleOf(attrs, stack.at(-1) ?? {});
    if (tag === 'svg') {
      const numbers = (attrs.viewBox ?? '')
        .trim()
        .split(/[\s,]+/)
        .map(Number);

      if (numbers.length === 4 && numbers.every(Number.isFinite)) {
        viewBox = numbers as ParsedSvg['viewBox'];
      } else if (attrs.width && attrs.height) {
        viewBox = [0, 0, Number.parseFloat(attrs.width), Number.parseFloat(attrs.height)];
      }
    }

    if (tag === 'svg' || tag === 'g') {
      if (!selfClosing) {
        stack.push(style);
      }

      continue;
    }

    if (SHAPES.has(tag)) {
      shapes.push({ tag, attrs, style });
    } else if (!IGNORED.has(tag)) {
      throw new Error(`unsupported element <${tag}>`);
    }
  }

  if (!viewBox || viewBox.slice(2).some((size) => !(size > 0))) {
    throw new Error('no usable viewBox');
  }

  return { viewBox, shapes };
}

const num = (value: string | undefined, fallback = 0) => {
  const parsed = Number.parseFloat(value ?? '');
  return Number.isFinite(parsed) ? parsed : fallback;
};

/** Numbers without float noise (`0.1 + 0.2` -> `0.3`). */
const fmt = (value: number) => String(Math.round(value * 1e4) / 1e4);

/** A shape as SVG path data. */
export function toPathData(tag: string, a: Record<string, string>): string {
  switch (tag) {
    case 'path':
      return (a.d ?? '').trim().replace(/\s+/g, ' ');
    case 'circle': {
      const [cx, cy, r] = [num(a.cx), num(a.cy), num(a.r)];
      return `M${fmt(cx - r)} ${fmt(cy)}a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(2 * r)} 0a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(-2 * r)} 0`;
    }

    case 'ellipse': {
      const [cx, cy, rx, ry] = [num(a.cx), num(a.cy), num(a.rx), num(a.ry)];
      return `M${fmt(cx - rx)} ${fmt(cy)}a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(2 * rx)} 0a${fmt(rx)} ${fmt(ry)} 0 1 0 ${fmt(-2 * rx)} 0`;
    }

    case 'line':
      return `M${fmt(num(a.x1))} ${fmt(num(a.y1))}L${fmt(num(a.x2))} ${fmt(num(a.y2))}`;
    case 'polyline':
    case 'polygon': {
      const points = (a.points ?? '')
        .trim()
        .split(/[\s,]+/)
        .filter(Boolean)
        .map(Number);

      const pairs: string[] = [];
      for (let i = 0; i + 1 < points.length; i += 2) {
        pairs.push(`${fmt(points[i]!)} ${fmt(points[i + 1]!)}`);
      }

      return pairs.length === 0 ? '' : `M${pairs.join('L')}${tag === 'polygon' ? 'Z' : ''}`;
    }

    case 'rect': {
      const [x, y, w, h] = [num(a.x), num(a.y), num(a.width), num(a.height)];
      // rx and ry differ in an SVG rect; the corners here are circular, using rx (else ry), never throwing.
      const r = Math.min(num(a.rx ?? a.ry), w / 2, h / 2);
      return r > 0
        ? `M${fmt(x + r)} ${fmt(y)}h${fmt(w - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(r)}v${fmt(h - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(r)}h${fmt(-(w - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(-r)}v${fmt(-(h - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(-r)}z`
        : `M${fmt(x)} ${fmt(y)}h${fmt(w)}v${fmt(h)}h${fmt(-w)}z`;
    }

    default:
      throw new Error(`unsupported element <${tag}>`);
  }
}

/** An SVG paint as an Android `#AARRGGBB`: `currentColor` and anything not a hex colour are white (the drawable is tinted anyway), `none` is `undefined`. */
export function paint(value: string | undefined): string | undefined {
  const v = (value ?? '').trim();
  if (v === 'none' || v === 'transparent') {
    return undefined;
  }

  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(v)?.[1];
  if (!hex) {
    return WHITE;
  }

  const full = hex.length === 3 ? [...hex].map((c) => c + c).join('') : hex;
  return `#FF${full.toUpperCase()}`;
}

const escapeXml = (value: string) => value.replace(/&/g, '&amp;').replace(/"/g, '&quot;');

/** The vector drawable XML for an SVG. A shape with neither fill nor stroke is dropped. */
export function svgToVectorDrawable(svg: string): string {
  const { viewBox, shapes } = parseSvg(svg);
  const [x, y, width, height] = viewBox;
  const paths: string[] = [];

  for (const { tag, attrs, style } of shapes) {
    const data = toPathData(tag, attrs);
    // A line has no inside, and a polyline is a stroke unless the SVG says to fill it; the SVG default (black) applies to the rest.
    const fill =
      tag === 'line' || (tag === 'polyline' && style.fill === undefined)
        ? undefined
        : paint(style.fill ?? 'black');

    const stroke = paint(style.stroke);
    if (!data || (!fill && !stroke)) {
      continue;
    }

    const parts = [`android:pathData="${escapeXml(data)}"`];
    if (fill) {
      parts.push(`android:fillColor="${fill}"`);
      if (style.fillRule === 'evenodd') {
        parts.push('android:fillType="evenOdd"');
      }
    }

    if (stroke) {
      parts.push(
        `android:strokeColor="${stroke}"`,
        `android:strokeWidth="${fmt(num(style.strokeWidth, 1))}"`,
        'android:strokeLineCap="round"',
        'android:strokeLineJoin="round"',
      );
    }

    paths.push(`<path ${parts.join(' ')} />`);
  }

  const offset = x !== 0 || y !== 0;
  const indent = offset ? '        ' : '    ';
  const body = paths.map((path) => `${indent}${path}`);
  const content = offset
    ? [
        `    <group android:translateX="${fmt(-x)}" android:translateY="${fmt(-y)}">`,
        ...body,
        '    </group>',
      ]
    : body;

  return [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="24dp" android:height="24dp"',
    `    android:viewportWidth="${fmt(width)}" android:viewportHeight="${fmt(height)}">`,
    ...content,
    '</vector>',
    '',
  ].join('\n');
}

// --- Kotlin ---

export interface IconEntry {
  /** The sanitised name, e.g. `arrow_down` or `heart_filled`. */
  name: string;
}

/** The Kotlin object for a pack: `object Lucide { val bath = Icon(R.drawable.ic_lucide_bath) … }`. */
export function renderKotlin(pack: string, entries: IconEntry[], appPackage: string): string {
  const prefix = sanitizeName(pack);
  return [
    `// Generated by \`hashsome android icons ${pack}\`. Do not edit by hand.`,
    `package ${appPackage}.icons`,
    '',
    `import ${appPackage}.R`,
    'import dev.hashsome.ui.Icon',
    '',
    `object ${packObjectName(pack)} {`,
    ...entries.map(
      ({ name }) => `    val ${camelCase(name)} = Icon(R.drawable.ic_${prefix}_${name})`,
    ),
    '}',
    '',
  ].join('\n');
}

// --- Finding the SVGs ---

/** The directory of `pkg` in the nearest `node_modules` at or above `root`. */
export function findPackageDir(root: string, pkg: string): string | undefined {
  for (let dir = resolve(root); ; dir = dirname(dir)) {
    const candidate = join(dir, 'node_modules', pkg);
    if (existsSync(candidate)) {
      return candidate;
    }

    if (dirname(dir) === dir) {
      return undefined;
    }
  }
}

export interface SvgSource {
  /** The sanitised name, suffix included. */
  name: string;
  file: string;
}

function listSvgs(dir: string, suffix: string): SvgSource[] {
  return readdirSync(dir)
    .filter((file) => file.endsWith('.svg'))
    .map((file) => ({ name: sanitizeName(file.slice(0, -4)) + suffix, file: join(dir, file) }));
}

/** The SVGs of `pack`: the `--dir` folder if given, else the known pack's folders in node_modules. */
export function sourcesFor(pack: string, root: string, dir?: string): SvgSource[] {
  if (dir) {
    if (!existsSync(dir)) {
      throw new Error(`${dir} does not exist`);
    }

    return listSvgs(dir, '');
  }

  const known = PACKS[pack];
  if (!known) {
    throw new Error(
      `Unknown icon pack "${pack}". Use ${Object.keys(PACKS).join(' or ')}, or --dir <folder of SVGs>.`,
    );
  }

  const base = findPackageDir(root, known.pkg);
  if (!base) {
    throw new Error(`${known.pkg} is not installed. Run: pnpm add -D ${known.pkg}`);
  }

  const found = known.folders
    .filter((folder) => existsSync(join(base, folder.path)))
    .flatMap((folder) => listSvgs(join(base, folder.path), folder.suffix));

  if (found.length === 0) {
    throw new Error(
      `No SVGs found in ${base} (expected ${known.folders.map((f) => f.path).join(', ')})`,
    );
  }

  return found;
}

// --- The command ---

export interface PackResult {
  pack: string;
  files: Record<string, string>;
  kotlin: string;
  skipped: string[];
  bytes: number;
}

/** Converts one pack: the drawable files by name, the Kotlin source, and the icons that could not be converted. */
export function convertPack(pack: string, sources: SvgSource[], appPackage: string): PackResult {
  const prefix = sanitizeName(pack);
  const files: Record<string, string> = {};
  const skipped: string[] = [];
  const seen = new Map<string, string>();
  const properties = new Map<string, string>();

  for (const { name, file } of sources.toSorted((a, b) => a.name.localeCompare(b.name))) {
    const clash = seen.get(name);
    if (clash) {
      throw new Error(`${file} and ${clash} both become "${name}"; rename one`);
    }

    seen.set(name, file);
    const property = camelCase(name);
    const same = properties.get(property);
    if (same) {
      // Whole packs have such pairs (lucide's arrow-down-0-1 and arrow-down-01): the first keeps the name, the other is skipped.
      skipped.push(`${file}: becomes the Kotlin property ${property} like ${same}`);
      continue;
    }

    properties.set(property, file);
    try {
      files[`ic_${prefix}_${name}.xml`] = svgToVectorDrawable(readFileSync(file, 'utf8'));
    } catch (error) {
      skipped.push(`${file}: ${(error as Error).message}`);
    }
  }

  const entries = Object.keys(files).map((file) => ({
    name: file.slice(`ic_${prefix}_`.length, -'.xml'.length),
  }));

  return {
    pack,
    files,
    kotlin: renderKotlin(pack, entries, appPackage),
    skipped,
    bytes: Object.values(files).reduce((sum, text) => sum + Buffer.byteLength(text), 0),
  };
}

const kb = (bytes: number) => `${(bytes / 1024).toFixed(1)} KB`;

/** `hashsome android icons`: converts icon packs to drawables and a Kotlin object each. */
export function iconsCommand(args: string[], root: string = resolve(process.cwd())): void {
  if (args.includes('--help') || args.includes('-h')) {
    console.log(USAGE);
    return;
  }

  const {
    positional: packs,
    values,
    flags,
  } = parseFlags(args, {
    values: ['dir', 'out', 'kotlin', 'package'],
    flags: ['dry-run', 'strict'],
  });

  if (packs.length === 0) {
    throw new Error(`Name at least one icon pack.\n\n${USAGE}`);
  }

  if (values.dir && packs.length > 1) {
    throw new Error('--dir takes exactly one pack name (it names the Kotlin object).');
  }

  const gradle = join(root, 'android', 'app', 'build.gradle.kts');
  const appPackage =
    values.package ??
    (existsSync(gradle) ? readGradleValue(readFileSync(gradle, 'utf8'), 'namespace') : undefined);

  if (!appPackage) {
    throw new Error(
      'Cannot tell the app package: no `namespace` in android/app/build.gradle.kts. Pass --package <name>.',
    );
  }

  const out = resolve(root, values.out ?? 'android/app/src/main/res/drawable');
  const dryRun = flags.has('dry-run');
  let total = 0;
  let skipped = 0;

  for (const pack of packs) {
    const result = convertPack(
      pack,
      sourcesFor(pack, root, values.dir ? resolve(root, values.dir) : undefined),
      appPackage,
    );

    const kotlinFile = values.kotlin
      ? packs.length > 1
        ? join(resolve(root, values.kotlin), `${packObjectName(pack)}.kt`)
        : resolve(root, values.kotlin)
      : join(
          root,
          'android/app/src/main/kotlin',
          ...appPackage.split('.'),
          'icons',
          `${packObjectName(pack)}.kt`,
        );

    if (!dryRun) {
      mkdirSync(out, { recursive: true });
      for (const [file, text] of Object.entries(result.files)) {
        writeFileSync(join(out, file), text);
      }

      mkdirSync(dirname(kotlinFile), { recursive: true });
      writeFileSync(kotlinFile, result.kotlin);
    }

    for (const line of result.skipped) {
      console.warn(`skipped ${line}`);
    }

    total += result.bytes;
    skipped += result.skipped.length;
    console.log(
      `${pack}: ${Object.keys(result.files).length} icons, ${kb(result.bytes)} -> ${out}\n  ${packObjectName(pack)} -> ${kotlinFile}`,
    );
  }

  console.log(
    `${dryRun ? `Dry run, nothing written (${kb(total)} in all)` : `Wrote ${kb(total)} in all`}; ${skipped} skipped.`,
  );

  if (flags.has('strict') && skipped > 0) {
    throw new Error(`${skipped} icon(s) skipped and --strict is set.`);
  }
}

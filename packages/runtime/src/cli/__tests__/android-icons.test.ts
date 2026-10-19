import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, test, vi } from 'vitest';
import {
  camelCase,
  convertPack,
  iconsCommand,
  paint,
  renderKotlin,
  sanitizeName,
  sourcesFor,
  svgToVectorDrawable,
  toPathData,
} from '../android-icons.ts';

const fixture = (name: string) => readFileSync(join(import.meta.dirname, 'fixtures', name), 'utf8');
const fixtures = join(import.meta.dirname, 'fixtures');

describe('sanitizeName', () => {
  test('keeps lower-case letters, digits and underscores', () => {
    expect(sanitizeName('arrow-down-left')).toBe('arrow_down_left');
    expect(sanitizeName('Heart Filled!')).toBe('heart_filled');
    expect(sanitizeName('--a__b--')).toBe('a_b');
    expect(sanitizeName('2fa')).toBe('2fa');
  });
});

describe('camelCase', () => {
  test('joins the words', () => {
    expect(camelCase('bath')).toBe('bath');
    expect(camelCase('arrow-down-left')).toBe('arrowDownLeft');
    expect(camelCase('heart_filled')).toBe('heartFilled');
  });

  test('a leading digit gets an underscore, a keyword gets backticks', () => {
    expect(camelCase('2fa')).toBe('_2fa');
    expect(camelCase('package')).toBe('`package`');
    expect(camelCase('infinity')).toBe('infinity');
  });
});

describe('paint', () => {
  test('currentColor and non-hex colours are white, none is nothing, hex is normalised', () => {
    expect(paint('currentColor')).toBe('#FFFFFFFF');
    expect(paint('black')).toBe('#FFFFFFFF');
    expect(paint('none')).toBeUndefined();
    expect(paint(undefined)).toBe('#FFFFFFFF');
    expect(paint('#f80')).toBe('#FFFF8800');
    expect(paint('#a1b2c3')).toBe('#FFA1B2C3');
  });
});

describe('toPathData', () => {
  test('converts each basic shape', () => {
    expect(toPathData('line', { x1: '1', y1: '2', x2: '3', y2: '4' })).toBe('M1 2L3 4');
    expect(toPathData('polyline', { points: '1,2 3,4' })).toBe('M1 2L3 4');
    expect(toPathData('polygon', { points: '1 2 3 4 5 6' })).toBe('M1 2L3 4L5 6Z');
    expect(toPathData('circle', { cx: '12', cy: '12', r: '4' })).toBe(
      'M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0',
    );

    expect(toPathData('ellipse', { cx: '10', cy: '10', rx: '4', ry: '2' })).toBe(
      'M6 10a4 2 0 1 0 8 0a4 2 0 1 0 -8 0',
    );

    expect(toPathData('rect', { x: '1', y: '2', width: '3', height: '4' })).toBe('M1 2h3v4h-3z');
    expect(toPathData('rect', { width: '10', height: '10', rx: '2' })).toContain('a2 2 0 0 1');
  });

  test('refuses an element it cannot convert', () => {
    expect(() => toPathData('text', {})).toThrow('unsupported');
  });
});

describe('svgToVectorDrawable', () => {
  test('a path icon: stroked white, round, width from the SVG', () => {
    const xml = svgToVectorDrawable(fixture('path-icon.svg'));
    expect(xml).toContain('android:width="24dp" android:height="24dp"');
    expect(xml).toContain('android:viewportWidth="24" android:viewportHeight="24"');
    expect(xml).toContain('android:pathData="M10 4 8 6"');
    expect(xml).toContain(
      'android:strokeColor="#FFFFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round"',
    );

    expect(xml).not.toContain('fillColor');
    expect(xml.match(/<path /g)).toHaveLength(2);
    expect(xml).not.toContain('license');
  });

  test('a circle and a line icon, with the SVG stroke width', () => {
    const xml = svgToVectorDrawable(fixture('circle-line.svg'));
    expect(xml.match(/<path /g)).toHaveLength(2);
    expect(xml).toContain('M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0');
    expect(xml).toContain('M12 2L12 5');
    expect(xml).toContain('android:strokeWidth="1.5"');
    expect(xml).not.toContain('fillColor');
  });

  test('a polyline icon is a stroke, not a fill', () => {
    const xml = svgToVectorDrawable(fixture('polyline.svg'));
    expect(xml).toContain('android:pathData="M20 6L9 17L4 12"');
    expect(xml).not.toContain('fillColor');
  });

  test('a filled icon: fill from the SVG, evenodd, the invisible tabler box dropped', () => {
    const xml = svgToVectorDrawable(fixture('filled.svg'));
    expect(xml.match(/<path /g)).toHaveLength(2);
    expect(xml).not.toContain('M0 0h24v24H0z');
    expect(xml).toContain('android:fillColor="#FFFFFFFF" android:fillType="evenOdd"');
    expect(xml).toContain('android:fillColor="#FFFF8800"');
    expect(xml).not.toContain('strokeColor');
  });

  test('a viewBox with an origin is translated; another size is kept', () => {
    const xml = svgToVectorDrawable(
      '<svg viewBox="-2 -4 32 32" fill="none" stroke="currentColor"><path d="M0 0L1 1"/></svg>',
    );

    expect(xml).toContain('android:viewportWidth="32" android:viewportHeight="32"');
    expect(xml).toContain('<group android:translateX="2" android:translateY="4">');
  });

  test('fill and stroke inherit down groups', () => {
    const xml = svgToVectorDrawable(
      '<svg viewBox="0 0 24 24"><g fill="none" stroke="currentColor"><path d="M0 0L1 1"/></g></svg>',
    );

    expect(xml).toContain('strokeColor');
    expect(xml).not.toContain('fillColor');
  });

  test('throws on an element it cannot convert, and on a missing viewBox', () => {
    expect(() => svgToVectorDrawable('<svg viewBox="0 0 24 24"><text>a</text></svg>')).toThrow(
      'unsupported element <text>',
    );

    expect(() => svgToVectorDrawable('<svg><path d="M0 0"/></svg>')).toThrow('viewBox');
  });
});

describe('renderKotlin', () => {
  test('one val per icon, in the app package', () => {
    const kotlin = renderKotlin(
      'lucide',
      [{ name: 'bath' }, { name: 'arrow_down' }, { name: 'package' }],
      'com.example.home',
    );

    expect(kotlin).toContain('package com.example.home.icons');
    expect(kotlin).toContain('import com.example.home.R');
    expect(kotlin).toContain('import dev.hashsome.ui.Icon');
    expect(kotlin).toContain('object Lucide {');
    expect(kotlin).toContain('    val bath = Icon(R.drawable.ic_lucide_bath)');
    expect(kotlin).toContain('    val arrowDown = Icon(R.drawable.ic_lucide_arrow_down)');
    expect(kotlin).toContain('    val `package` = Icon(R.drawable.ic_lucide_package)');
  });
});

describe('convertPack and sourcesFor', () => {
  test('converts a folder of SVGs, sorted, with names sanitised', () => {
    const sources = sourcesFor('mine', '/', fixtures);
    const result = convertPack('mine', sources, 'com.x');
    expect(Object.keys(result.files)).toEqual([
      'ic_mine_circle_line.xml',
      'ic_mine_filled.xml',
      'ic_mine_path_icon.xml',
      'ic_mine_polyline.xml',
    ]);

    expect(result.kotlin).toContain('val circleLine = Icon(R.drawable.ic_mine_circle_line)');
    expect(result.skipped).toEqual([]);
    expect(result.bytes).toBeGreaterThan(0);
  });

  test('an unusable icon is skipped, not fatal', () => {
    const dir = mkdtempSync(join(tmpdir(), 'icons-'));
    try {
      writeFileSync(join(dir, 'bad.svg'), '<svg viewBox="0 0 24 24"><text/></svg>');
      writeFileSync(join(dir, 'good.svg'), fixture('polyline.svg'));
      const result = convertPack('p', sourcesFor('p', '/', dir), 'com.x');
      expect(Object.keys(result.files)).toEqual(['ic_p_good.xml']);
      expect(result.skipped).toHaveLength(1);
    } finally {
      rmSync(dir, { recursive: true });
    }
  });

  test('two files that sanitise to one name are an error', () => {
    const dir = mkdtempSync(join(tmpdir(), 'icons-'));
    try {
      writeFileSync(join(dir, 'a-b.svg'), fixture('polyline.svg'));
      writeFileSync(join(dir, 'a_b.svg'), fixture('polyline.svg'));
      expect(() => convertPack('p', sourcesFor('p', '/', dir), 'com.x')).toThrow('both become');
    } finally {
      rmSync(dir, { recursive: true });
    }
  });

  test('finds lucide and tabler (outline and filled) in node_modules', () => {
    const root = mkdtempSync(join(tmpdir(), 'icons-'));
    try {
      mkdirSync(join(root, 'node_modules/lucide-static/icons'), { recursive: true });
      writeFileSync(
        join(root, 'node_modules/lucide-static/icons/bath.svg'),
        fixture('polyline.svg'),
      );

      mkdirSync(join(root, 'node_modules/@tabler/icons/icons/outline'), { recursive: true });
      mkdirSync(join(root, 'node_modules/@tabler/icons/icons/filled'), { recursive: true });
      writeFileSync(
        join(root, 'node_modules/@tabler/icons/icons/outline/heart.svg'),
        fixture('polyline.svg'),
      );

      writeFileSync(
        join(root, 'node_modules/@tabler/icons/icons/filled/heart.svg'),
        fixture('filled.svg'),
      );

      expect(sourcesFor('lucide', root).map((s) => s.name)).toEqual(['bath']);
      expect(
        sourcesFor('tabler', root)
          .map((s) => s.name)
          .toSorted(),
      ).toEqual(['heart', 'heart_filled']);
    } finally {
      rmSync(root, { recursive: true });
    }
  });

  test('says what to install, and rejects an unknown pack without --dir', () => {
    expect(() => sourcesFor('lucide', mkdtempSync(join(tmpdir(), 'icons-')))).toThrow(
      'lucide-static is not installed',
    );

    expect(() => sourcesFor('nope', '/')).toThrow('Unknown icon pack');
  });
});

describe('iconsCommand', () => {
  const dirs: string[] = [];
  afterEach(() => {
    vi.restoreAllMocks();
    for (const dir of dirs.splice(0)) {
      rmSync(dir, { recursive: true });
    }
  });

  const project = () => {
    const root = mkdtempSync(join(tmpdir(), 'icons-'));
    dirs.push(root);
    return root;
  };

  test('writes drawables and the Kotlin object, package from the namespace', () => {
    const root = project();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    mkdirSync(join(root, 'android/app'), { recursive: true });
    writeFileSync(
      join(root, 'android/app/build.gradle.kts'),
      'android {\n  namespace = "com.example.home"\n}\n',
    );

    iconsCommand(['mine', '--dir', fixtures], root);

    expect(readdirSync(join(root, 'android/app/src/main/res/drawable'))).toContain(
      'ic_mine_filled.xml',
    );

    const kotlin = readFileSync(
      join(root, 'android/app/src/main/kotlin/com/example/home/icons/Mine.kt'),
      'utf8',
    );

    expect(kotlin).toContain('package com.example.home.icons');
  });

  test('--dry-run writes nothing', () => {
    const root = project();
    vi.spyOn(console, 'log').mockImplementation(() => {});
    iconsCommand(['mine', '--dir', fixtures, '--package', 'com.x', '--dry-run'], root);
    expect(readdirSync(root)).toEqual([]);
  });

  test('needs a package, a pack, and one pack with --dir', () => {
    const root = project();
    expect(() => iconsCommand(['mine', '--dir', fixtures], root)).toThrow('--package');
    expect(() => iconsCommand([], root)).toThrow('at least one icon pack');
    expect(() => iconsCommand(['a', 'b', '--dir', fixtures, '--package', 'x'], root)).toThrow(
      'exactly one',
    );
  });
});

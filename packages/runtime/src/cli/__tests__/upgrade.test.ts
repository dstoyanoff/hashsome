import { describe, expect, test } from 'vitest';
import {
  bumpVersionCatalog,
  describeChanges,
  hashsomeDependencies,
  otherPackageManager,
  upgradeCommand,
} from '../upgrade.ts';

describe('hashsomeDependencies', () => {
  test('finds the @hashsome packages and leaves everything else', () => {
    expect(
      hashsomeDependencies({
        dependencies: { '@hashsome/ui': '0.1.0', react: '19.3.0', '@hashsome/runtime': '0.1.0' },
        devDependencies: { '@hashsome/core': '0.1.0', vite: '8.3.1' },
      }),
    ).toEqual({ upgrade: ['@hashsome/core', '@hashsome/runtime', '@hashsome/ui'], linked: [] });
  });

  test('a package linked from a local checkout is not upgraded', () => {
    expect(
      hashsomeDependencies({
        dependencies: {
          '@hashsome/ui': 'link:../hashsome/packages/ui',
          '@hashsome/core': 'workspace:*',
          '@hashsome/runtime': '0.1.0',
        },
      }),
    ).toEqual({
      upgrade: ['@hashsome/runtime'],
      linked: ['@hashsome/core', '@hashsome/ui'],
    });
  });

  test('a project without them has nothing', () => {
    expect(hashsomeDependencies({ dependencies: { react: '19.3.0' } })).toEqual({
      upgrade: [],
      linked: [],
    });
  });
});

describe('otherPackageManager', () => {
  const none = () => false;

  test('pnpm, named or by lockfile, is not another manager', () => {
    expect(otherPackageManager({ packageManager: 'pnpm@12.6.0' }, none)).toBeUndefined();
    expect(otherPackageManager({}, (f) => f === 'pnpm-lock.yaml')).toBeUndefined();
    expect(otherPackageManager({}, none)).toBeUndefined();
  });

  test('another one is found by the name the project gives, or by its lockfile', () => {
    expect(
      otherPackageManager({ packageManager: 'yarn@4.1.0' }, (f) => f === 'pnpm-lock.yaml'),
    ).toBe('yarn');

    expect(otherPackageManager({}, (f) => f === 'yarn.lock')).toBe('yarn');
    expect(otherPackageManager({}, (f) => f === 'bun.lock')).toBe('bun');
    expect(otherPackageManager({}, (f) => f === 'package-lock.json')).toBe('npm');
  });
});

describe('upgradeCommand', () => {
  test('is pnpm update to the latest release', () => {
    expect(upgradeCommand(['@hashsome/core', '@hashsome/ui'])).toEqual([
      'pnpm',
      'update',
      '--latest',
      '@hashsome/core',
      '@hashsome/ui',
    ]);
  });
});

describe('describeChanges', () => {
  test('lists the packages whose version moved', () => {
    expect(
      describeChanges(
        { dependencies: { '@hashsome/ui': '0.1.0', '@hashsome/core': '0.1.0' } },
        { dependencies: { '@hashsome/ui': '0.2.0', '@hashsome/core': '0.1.0' } },
      ),
    ).toEqual(['  @hashsome/ui  0.1.0 -> 0.2.0']);
  });

  test('nothing moved is nothing listed', () => {
    const same = { dependencies: { '@hashsome/ui': '0.1.0' } };
    expect(describeChanges(same, same)).toEqual([]);
  });
});

describe('bumpVersionCatalog', () => {
  const toml = `[versions]
agp = "8.7.0"
hashsome = "0.19.2"
kotlin = "2.0.21"

[libraries]
hashsome-ui = { module = "dev.hashsome:ui", version.ref = "hashsome" }
`;

  test('rewrites the hashsome version and nothing else', () => {
    const result = bumpVersionCatalog(toml, '0.20.0');
    expect(result.changed).toBe(true);
    expect(result.text).toBe(toml.replace('hashsome = "0.19.2"', 'hashsome = "0.20.0"'));
  });

  test('is a no-op at the same version, without the line, or outside [versions]', () => {
    expect(bumpVersionCatalog(toml, '0.19.2')).toEqual({ text: toml, changed: false });
    expect(bumpVersionCatalog('[versions]\nagp = "8.7.0"\n', '1.0.0').changed).toBe(false);
    const elsewhere = '[bundles]\nhashsome = "0.1.0"\n';
    expect(bumpVersionCatalog(elsewhere, '1.0.0')).toEqual({ text: elsewhere, changed: false });
  });

  test('leaves a longer key like hashsome-extra alone', () => {
    const text = '[versions]\nhashsome-extra = "1.0.0"\n';
    expect(bumpVersionCatalog(text, '2.0.0').changed).toBe(false);
  });
});

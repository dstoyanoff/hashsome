import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, test } from 'vitest';
import { describeStatus, parityProblems, renderParity, statusProblem } from './parity-lib.ts';
import { catalogNames, type CatalogName } from './ui-catalog-lib.ts';

const names: CatalogName[] = [
  { name: 'LightTile', kind: 'component', group: 'Entity components' },
  { name: 'Grid', kind: 'component', group: 'Layout' },
  { name: 'useVolumeControl', kind: 'hook', group: 'Hooks and provider' },
];

describe('statusProblem', () => {
  test.each([
    'native',
    'planned',
    'planned#12',
    'web-only',
    'native:VolumeControl',
    'native-only:uses the system share sheet',
  ])('accepts %s', (status) => {
    expect(statusProblem(status)).toBeUndefined();
  });

  test.each(['', 'done', 'planned#', 'planned#abc', 'native:', 'native-only:', 'native-only:  '])(
    'rejects "%s"',
    (status) => {
      expect(statusProblem(status)).toBeTypeOf('string');
    },
  );

  test('rejects a non-string', () => {
    expect(statusProblem(3)).toBeTypeOf('string');
  });
});

describe('parityProblems', () => {
  test('is empty when every name has a well-formed entry', () => {
    expect(
      parityProblems(names, {
        LightTile: 'native',
        Grid: 'planned#3',
        useVolumeControl: 'planned',
      }),
    ).toEqual([]);
  });

  test('lists a missing name', () => {
    expect(parityProblems(names, { LightTile: 'native', Grid: 'native' })).toEqual([
      expect.stringContaining('missing: useVolumeControl'),
    ]);
  });

  test('lists an entry no longer exported, but not a native-only one', () => {
    const parity = {
      LightTile: 'native',
      Grid: 'native',
      useVolumeControl: 'native',
      Gone: 'planned',
      Swipe: 'native-only:touch gesture',
    };

    expect(parityProblems(names, parity)).toEqual([expect.stringContaining('stale: Gone')]);
  });

  test('lists a malformed status', () => {
    expect(
      parityProblems(names, { LightTile: 'done', Grid: 'native', useVolumeControl: 'native' }),
    ).toEqual([expect.stringContaining('malformed: LightTile')]);
  });
});

describe('describeStatus', () => {
  test('splits the status word from the notes', () => {
    expect(describeStatus('native:VolumeControl')).toEqual({
      status: 'native',
      notes: '`VolumeControl`',
    });

    expect(describeStatus('planned#9')).toEqual({ status: 'planned', notes: '#9' });
    expect(describeStatus('native-only: why')).toEqual({ status: 'native-only', notes: 'why' });
    expect(describeStatus('web-only')).toEqual({ status: 'web-only', notes: '' });
  });
});

describe('renderParity', () => {
  test('groups entity components, layout and hooks', () => {
    const text = renderParity(names, {
      LightTile: 'native',
      Grid: 'web-only',
      useVolumeControl: 'native:VolumeControl',
    });

    expect(text.indexOf('## Entity components')).toBeLessThan(text.indexOf('## Layout'));
    expect(text.indexOf('## Layout')).toBeLessThan(text.indexOf('## Hooks'));
    expect(text).toContain('| `useVolumeControl` | hook | native | `VolumeControl` |');
    expect(text).toContain('| `Grid` | component | web-only |  |');
  });
});

describe('the checked-in parity.json', () => {
  test('matches what @hashsome/ui exports', () => {
    const file = join(import.meta.dirname, '..', 'android', 'parity.json');
    expect(parityProblems(catalogNames(), JSON.parse(readFileSync(file, 'utf8')))).toEqual([]);
  });
});

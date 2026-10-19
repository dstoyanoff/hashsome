import { expect, it } from 'vitest';
import { fixtureDrift, generateFixtures } from './generate-android-fixtures.ts';

it('the checked-in Android fixtures are what the mocks produce now', async () => {
  const files = await generateFixtures();
  expect(await fixtureDrift(files), 'run `pnpm generate:fixtures` and commit the result').toEqual(
    [],
  );
});

it('the fixtures cover every entity kind, with unavailable variants', async () => {
  const files = await generateFixtures();
  const entities = [...files]
    .filter(([path]) => path.startsWith('entities/'))
    .map(([, text]) => JSON.parse(text).entity as { kind: string; availability: string });

  for (const kind of [
    'light',
    'switch',
    'climate',
    'action',
    'sensor',
    'person',
    'weather',
    'mediaPlayer',
    'generic',
  ]) {
    const states = entities.filter((e) => e.kind === kind).map((e) => e.availability);
    expect(states).toContain('ready');
    expect(states).toContain('unavailable');
  }
});

it('the generator is deterministic', async () => {
  const a = await generateFixtures();
  const b = await generateFixtures();
  expect([...b]).toEqual([...a]);
});

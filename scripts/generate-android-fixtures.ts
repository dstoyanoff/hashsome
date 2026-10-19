/**
 * Generates `android/testing/src/main/resources/fixtures/**`: the JSON the runtime puts on the wire, taken from the
 * `@hashsome/core` mocks and the two integrations' mocks, for the native client's parsers to be tested against.
 * Run via `pnpm generate:fixtures`. CI (and `generate-android-fixtures.test.ts`) fails if the output would change.
 *
 * Every file is one websocket frame as `encodeMessage` writes it (`entity`, `status`, `pong`, `result`), pretty-printed
 * by the repo's formatter so `pnpm format:check` passes. `index.json` lists them all, since a jar cannot be listed.
 * The mocks stamp the time they run at in places, so the clock is pinned to `NOW` while generating.
 */
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  encodeMessage,
  LocalClient,
  mockAction,
  mockClimate,
  mockGeneric,
  mockLight,
  mockMediaPlayer,
  mockPerson,
  mockSensor,
  mockSwitch,
  mockWeather,
  type Entity,
  type EntityInput,
  type MockIntegration,
  type ServerMessage,
} from '../packages/core/src/index.ts';
import { createMock as createHomeAssistantMock } from '../packages/integrations/home-assistant/src/mock.ts';
import { createMock as createMusicAssistantMock } from '../packages/integrations/music-assistant/src/mock.ts';
import { formatWithRepoConfig } from './format.ts';

// The mocks derive days and hours from the local time zone as well as the clock: pin it, so the output is the same on every machine and in CI.
process.env.TZ = 'UTC';

const OUT_DIR = join(
  import.meta.dirname,
  '..',
  'android',
  'testing',
  'src',
  'main',
  'resources',
  'fixtures',
);

/** The moment the mocks think it is: forecasts, histories and logbooks are made relative to it. */
const NOW = Date.parse('2026-03-01T12:00:00.000Z');
const STAMP = new Date(NOW - 60_000).toISOString();

/** Runs [fn] with `Date.now()` and `new Date()` at [NOW], then puts the clock back. */
async function atFixedTime<T>(fn: () => Promise<T>): Promise<T> {
  const RealDate = Date;
  class FixedDate extends RealDate {
    constructor(...args: unknown[]) {
      super(...((args.length === 0 ? [NOW] : args) as [number]));
    }

    static override now(): number {
      return NOW;
    }
  }

  globalThis.Date = FixedDate as DateConstructor;
  try {
    return await fn();
  } finally {
    globalThis.Date = RealDate;
  }
}

const artwork = '/_hashsome/asset/ma?path=%2Fimageproxy%2Fcover.jpg';

/** The entities on top of what the integrations' own mocks have, so every kind has its ready and unavailable variants. */
const extras = (): Record<string, EntityInput> => ({
  'light.fx_color_on': mockLight({
    name: 'Color light on',
    on: true,
    brightness: 0.5,
    color: { mode: 'color', hue: 280, saturation: 80, rgb: [170, 51, 255] },
    capabilities: { brightness: true, color: true },
    lastChanged: STAMP,
    lastUpdated: STAMP,
  }),
  'light.fx_temperature_on': mockLight({
    name: 'Warm white',
    on: true,
    brightness: 0.8,
    color: { mode: 'temperature', kelvin: 2700 },
    capabilities: {
      brightness: true,
      colorTemperature: true,
      kelvinRange: { min: 2000, max: 6500 },
    },
  }),
  'light.fx_unavailable': mockLight({ name: 'Gone light', availability: 'unavailable' }),
  'switch.fx_on': mockSwitch({ name: 'Plug on', on: true, lastChanged: STAMP }),
  'switch.fx_unavailable': mockSwitch({ name: 'Plug gone', availability: 'unavailable' }),
  'climate.fx_heating': mockClimate({
    name: 'Floor heating',
    mode: 'heat',
    action: 'heating',
    targetTemperature: 23,
    currentTemperature: 21.5,
    humidity: 48,
    preset: 'comfort',
    capabilities: {
      modes: ['off', 'heat', 'auto'],
      presets: ['comfort', 'eco'],
      range: { min: 10, max: 28 },
    },
  }),
  'climate.fx_unavailable': mockClimate({ name: 'Heater gone', availability: 'unavailable' }),
  'action.fx_ready': mockAction({ name: 'Scene', lastTriggered: STAMP }),
  'action.fx_unavailable': mockAction({ name: 'Script gone', availability: 'unavailable' }),
  'sensor.fx_text': mockSensor({ name: 'Daylight', value: 'on', measurement: 'daylight' }),
  'sensor.fx_unavailable': mockSensor({
    name: 'Sensor gone',
    value: 'unavailable',
    availability: 'unavailable',
  }),
  'sensor.fx_unknown': mockSensor({ name: 'Sensor silent', value: '', availability: 'unknown' }),
  'person.fx_unavailable': mockPerson({ name: 'Nobody', availability: 'unavailable' }),
  'person.fx_zone': mockPerson({
    name: 'Sam',
    location: 'Work',
    pictureUrl: '/_hashsome/asset/ha?path=%2Fapi%2Fimage%2Fsam.png',
  }),
  'weather.fx_ready': mockWeather({
    name: 'Home weather',
    humidity: 71,
    condition: 'partlycloudy',
  }),
  'weather.fx_unavailable': mockWeather({ name: 'Weather gone', availability: 'unavailable' }),
  'generic.fx_value': mockGeneric({ name: 'Mystery', value: '42', unit: 'units' }),
  'generic.fx_unavailable': mockGeneric({
    name: 'Mystery gone',
    value: 'unavailable',
    availability: 'unavailable',
  }),
  'media_player.fx_playing': mockMediaPlayer({
    name: 'Playing',
    playback: 'playing',
    media: {
      title: 'Blank Space',
      artist: 'Taylor Swift',
      album: '1989',
      artworkUrl: artwork,
    },
    source: { id: 'al-1989', title: '1989', kind: 'album' },
    position: 64,
    duration: 231,
    positionUpdatedAt: STAMP,
    shuffle: true,
    volume: 0.4,
    muted: false,
    group: { leader: 'ha:media_player.fx_playing', members: ['ha:media_player.fx_paused'] },
    groupable: ['ha:media_player.fx_paused', 'ha:media_player.fx_idle'],
    capabilities: {
      volume: true,
      browse: true,
      search: true,
      seek: true,
      shuffle: true,
      queue: true,
      transfer: true,
      group: true,
    },
  }),
  'media_player.fx_paused': mockMediaPlayer({
    name: 'Paused',
    playback: 'paused',
    media: { title: 'Dreams', artist: 'Fleetwood Mac' },
    position: 95,
    duration: 214,
    positionUpdatedAt: STAMP,
    volume: 0.25,
    muted: true,
    capabilities: { volume: true, seek: true },
  }),
  'media_player.fx_idle': mockMediaPlayer({ name: 'Idle', playback: 'idle', volume: 0.15 }),
  'media_player.fx_unavailable': mockMediaPlayer({
    name: 'Player gone',
    availability: 'unavailable',
  }),
});

/** A frame the runtime might send from a newer version: a kind this app has never heard of. */
const futureKind = {
  type: 'entity',
  ref: 'ha:vacuum.future',
  entity: {
    ref: 'ha:vacuum.future',
    kind: 'vacuum',
    name: 'Robot from the future',
    availability: 'ready',
    value: 'docked',
    unit: 'state',
    battery: 80,
  },
};

type Files = Map<string, string>;

const ref = (value: string) => value as Entity['ref'];

const ERROR = 'Unknown integration "nope"';

/** A message as the wire carries it: through `encodeMessage`, so a key that is `undefined` is left out. */
const frame = (message: ServerMessage) => JSON.parse(encodeMessage(message)) as unknown;

const result = (id: number, data: unknown) =>
  frame({ type: 'result', id, ok: true, ...(data !== undefined ? { data } : {}) });

const refOf = (integration: MockIntegration, id: string) => `${integration.id}:${id}`;

const entityFile = (entityRef: string) => `entities/${entityRef.replace(':', '-')}.json`;

/** Every fixture file, by path relative to the fixtures directory, as formatted text. */
export async function generateFixtures(): Promise<Files> {
  return atFixedTime(async () => {
    const files: Files = new Map();
    const put = async (path: string, value: unknown) => {
      files.set(path, await formatWithRepoConfig(path, JSON.stringify(value)));
    };

    const ha = createHomeAssistantMock({ entities: extras() });
    const ma = createMusicAssistantMock();
    const client = new LocalClient([ha, ma]);
    client.connect();

    // Entities, as the proxy sends them.
    for (const integration of [ha, ma]) {
      for (const entity of integration.listEntities()) {
        await put(entityFile(entity.ref), frame({ type: 'entity', ref: entity.ref, entity }));
      }
    }

    await put('entities/ha-vacuum.future.json', futureKind);
    await put(
      'entity-null.json',
      frame({ type: 'entity', ref: 'ha:light.gone' as Entity['ref'], entity: null }),
    );

    // Connection status of an integration, in each state.
    for (const status of ['connecting', 'connected', 'disconnected', 'error'] as const) {
      await put(`status/${status}.json`, frame({ type: 'status', integration: 'ha', status }));
    }

    await put('pong.json', frame({ type: 'pong' }));

    // Answers to queries, as `answer` in the proxy sends them. A command that worked has no data; one that failed has an error.
    const player = ref(refOf(ma, 'living_room'));
    const queuePlayer = ref('ha:media_player.fx_playing');
    const weather = ref('ha:weather.fx_ready');
    const answers: [string, () => Promise<unknown>][] = [
      ['browse-root', () => client.browse(player, {})],
      ['browse-shelf', () => client.browse(player, { path: 'playlists' })],
      ['browse-tracks', () => client.browse(player, { path: 'al-1989' })],
      ['browse-search', () => client.browse(player, { search: 'mgmt' })],
      ['browse-search-kind', () => client.browse(player, { search: 'a', kind: 'album' })],
      ['queue', () => client.queue(queuePlayer, {})],
      ['queue-limited', () => client.queue(queuePlayer, { limit: 5, duration: true })],
      ['queue-empty', () => client.queue(ref('ha:media_player.fx_idle'), {})],
      ['forecast-daily', () => client.forecast(weather, { type: 'daily' })],
      ['forecast-hourly', () => client.forecast(weather, { type: 'hourly' })],
      ['forecast-twice-daily', () => client.forecast(weather, { type: 'twice_daily' })],
      ['history-measurement', () => client.history(ref('ha:sensor.temperature'), { range: '1d' })],
      [
        'history-total',
        () => client.history(ref('ha:sensor.energy'), { range: '1w', bucket: '1d' }),
      ],
      ['history-empty', () => client.history(ref('ha:light.plain'), { range: '1h' })],
      ['logbook', () => client.logbook(ref('ha:light.plain'), { limit: 12 })],
      ['logbook-empty', () => client.logbook(ref('ha:sensor.temperature'), {})],
      ['command-ok', () => Promise.resolve(undefined)],
    ];

    let id = 0;
    for (const [name, ask] of answers) {
      id += 1;
      await put(`results/${name}.json`, result(id, await ask()));
    }

    await put('results/error.json', frame({ type: 'result', id: 99, ok: false, error: ERROR }));

    const names = [...files.keys()].toSorted();
    files.set('index.json', await formatWithRepoConfig('index.json', JSON.stringify(names)));
    return files;
  });
}

/** Paths under [dir], relative to it. */
function listFiles(dir: string): string[] {
  if (!existsSync(dir)) {
    return [];
  }

  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory()
      ? listFiles(join(dir, entry.name)).map((path) => `${entry.name}/${path}`)
      : [entry.name],
  );
}

/** Where the fixtures differ from what is checked in: paths that are missing, stale or extra. */
export async function fixtureDrift(files: Files, dir = OUT_DIR): Promise<string[]> {
  const drift: string[] = [];
  for (const [path, text] of files) {
    const file = join(dir, path);
    if (!existsSync(file) || readFileSync(file, 'utf8') !== text) {
      drift.push(path);
    }
  }

  for (const path of listFiles(dir)) {
    if (!files.has(path)) {
      drift.push(path);
    }
  }

  return drift.toSorted();
}

async function main() {
  const files = await generateFixtures();
  rmSync(OUT_DIR, { recursive: true, force: true });
  for (const [path, text] of files) {
    const file = join(OUT_DIR, path);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, text);
  }

  console.log(`Wrote ${files.size} fixtures to ${relative(process.cwd(), OUT_DIR)}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main();
}

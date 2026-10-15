import { BaseIntegration } from './base-integration.ts';
import { parseEntityRef, UnknownEntityError } from './entity.ts';
import { mockForecast } from './mock-forecast.ts';
import { mockHistory } from './mock-history.ts';
import { mockLogbook } from './mock-logbook.ts';
import { mockQueue } from './mock-queue.ts';
import {
  isCommandOf,
  type ActionEntity,
  type BrowseItem,
  type BrowseQuery,
  type BrowseResult,
  type ClimateEntity,
  type Entity,
  type EntityInput,
  type ForecastQuery,
  type ForecastResult,
  type GenericEntity,
  type HistoryQuery,
  type HistoryResult,
  type LogbookQuery,
  type LogbookResult,
  type QueueQuery,
  type QueueResult,
  type LightEntity,
  type MediaPlayerEntity,
  type PersonEntity,
  type SensorEntity,
  type SwitchEntity,
  type WeatherEntity,
} from './model/index.ts';

// ── builders ───────────────────────────────────────────────────────────────────────────────────
// Fixtures for tests, the gallery and mock instances. Each takes only what matters and fills in
// the rest (name, availability, capabilities that follow from what was given).

type Init<E extends { kind: string; ref: unknown }, K extends keyof E = never> = Partial<
  Omit<E, 'ref' | 'kind' | K>
>;

export function mockMediaPlayer(
  init: Init<MediaPlayerEntity, 'capabilities'> & {
    capabilities?: Partial<MediaPlayerEntity['capabilities']>;
  } = {},
): EntityInput {
  const { capabilities, ...rest } = init;
  return {
    kind: 'mediaPlayer',
    name: 'Player',
    availability: 'ready',
    playback: 'idle',
    muted: false,
    ...rest,
    capabilities: {
      volume: rest.volume !== undefined,
      mute: true,
      next: true,
      previous: true,
      browse: false,
      search: false,
      seek: false,
      shuffle: false,
      queue: false,
      transfer: false,
      group: false,
      ...capabilities,
    },
  };
}

export function mockLight(
  init: Init<LightEntity, 'capabilities'> & {
    capabilities?: Partial<LightEntity['capabilities']>;
  } = {},
): EntityInput {
  const { capabilities, ...rest } = init;
  return {
    kind: 'light',
    name: 'Light',
    availability: 'ready',
    on: false,
    ...rest,
    capabilities: {
      brightness: rest.brightness !== undefined,
      colorTemperature: false,
      color: false,
      ...capabilities,
    },
  };
}

export function mockClimate(
  init: Init<ClimateEntity, 'capabilities'> & {
    capabilities?: Partial<ClimateEntity['capabilities']>;
  } = {},
): EntityInput {
  const { capabilities, ...rest } = init;
  return {
    kind: 'climate',
    name: 'Climate',
    availability: 'ready',
    mode: 'off',
    unit: '°C',
    ...rest,
    capabilities: {
      modes: ['off', 'heat'],
      presets: [],
      targetTemperature: rest.targetTemperature !== undefined,
      step: 0.5,
      range: { min: 5, max: 35 },
      ...capabilities,
    },
  };
}

export function mockSensor(init: Init<SensorEntity> & { value: string }): EntityInput {
  const numeric = Number(init.value);
  return {
    kind: 'sensor',
    name: 'Sensor',
    availability: 'ready',
    ...(init.value.trim() !== '' && Number.isFinite(numeric) ? { numeric } : {}),
    ...init,
  };
}

export function mockSwitch(init: Init<SwitchEntity> = {}): EntityInput {
  return { kind: 'switch', name: 'Switch', availability: 'ready', on: false, ...init };
}

export function mockAction(init: Init<ActionEntity> = {}): EntityInput {
  return { kind: 'action', name: 'Action', availability: 'ready', ...init };
}

export function mockPerson(init: Init<PersonEntity> = {}): EntityInput {
  const location = init.location ?? 'home';
  return {
    kind: 'person',
    name: 'Person',
    availability: 'ready',
    home: location === 'home',
    location,
    ...init,
  };
}

export function mockWeather(init: Init<WeatherEntity> = {}): EntityInput {
  return {
    kind: 'weather',
    name: 'Weather',
    availability: 'ready',
    condition: 'sunny',
    temperature: 20,
    unit: '°C',
    windSpeed: 14,
    windBearing: 225,
    windUnit: 'km/h',
    uvIndex: 3,
    cloudCoverage: 40,
    apparentTemperature: 18,
    pressure: 1015,
    pressureUnit: 'hPa',
    precipitationUnit: 'mm',
    forecasts: ['daily', 'hourly'],
    ...init,
  };
}

export function mockGeneric(init: Init<GenericEntity> & { value: string }): EntityInput {
  return { kind: 'generic', name: 'Entity', availability: 'ready', ...init };
}

// ── the integration ────────────────────────────────────────────────────────────────────────────

export interface MockCall {
  entityId: string;
  command: string;
  args?: Record<string, unknown>;
}

/** A fake media library: the items inside each folder, by folder id (`root` for the top level). */
export type MockLibrary = Record<string, { title?: string; items: BrowseItem[] }>;

/** Cover art for the mock library: a stable picture per item, from a placeholder service. */
const cover = (id: string) => `https://picsum.photos/seed/${id}/400`;

const libraryTrack = (id: string, title: string, artist: string): BrowseItem => ({
  id,
  title,
  subtitle: artist,
  artworkUrl: cover(id),
  kind: 'track',
  playable: true,
  expandable: false,
});

const libraryFolder = (
  id: string,
  title: string,
  kind: BrowseItem['kind'],
  subtitle?: string,
  holds?: BrowseItem['kind'],
): BrowseItem => ({
  id,
  title,
  ...(subtitle ? { subtitle } : {}),
  ...(holds ? { holds } : {}),
  ...(kind === 'folder' ? {} : { artworkUrl: cover(id) }),
  kind,
  playable: kind !== 'folder',
  expandable: true,
});

/** A small library with the shapes a real one has: shelves, each holding playlists, albums,
 * artists or stations, which in turn hold tracks. */
export function mockLibrary(): MockLibrary {
  return {
    root: {
      items: [
        libraryFolder('recent', 'Recently played', 'folder'),
        libraryFolder('playlists', 'Playlists', 'folder', undefined, 'playlist'),
        libraryFolder('albums', 'Albums', 'folder', undefined, 'album'),
        libraryFolder('artists', 'Artists', 'folder', undefined, 'artist'),
        libraryFolder('radio', 'Radio', 'folder', undefined, 'radio'),
      ],
    },
    recent: {
      title: 'Recently played',
      items: [
        libraryTrack('t-blank-space', 'Blank Space', 'Taylor Swift'),
        libraryTrack('t-dreams', 'Dreams', 'Fleetwood Mac'),
        libraryTrack('t-kids', 'Kids', 'MGMT'),
        libraryTrack('t-style', 'Style', 'Taylor Swift'),
        libraryTrack('t-electric', 'Electric Feel', 'MGMT'),
        libraryTrack('t-go-your-own-way', 'Go Your Own Way', 'Fleetwood Mac'),
        libraryTrack('t-borderline', 'Borderline', 'Tame Impala'),
      ],
    },
    playlists: {
      title: 'Playlists',
      items: [
        libraryFolder('pl-morning', 'Morning coffee', 'playlist', '24 tracks'),
        libraryFolder('pl-dinner', 'Dinner party', 'playlist', '41 tracks'),
        libraryFolder('pl-focus', 'Deep focus', 'playlist', '60 tracks'),
        // A title far too long for any tile, to see how a layout copes with one.
        libraryFolder(
          'pl-bass',
          'BASS BOOSTED SONGS 2026 🔊 REMIXES 🔊 🔊',
          'playlist',
          'A playlist with a very, very long name to show how titles are held to their room',
        ),
      ],
    },
    albums: {
      title: 'Albums',
      items: [
        libraryFolder('al-1989', '1989', 'album', 'Taylor Swift'),
        libraryFolder('al-rumours', 'Rumours', 'album', 'Fleetwood Mac'),
        libraryFolder('al-oracular', 'Oracular Spectacular', 'album', 'MGMT'),
        libraryFolder('al-currents', 'Currents', 'album', 'Tame Impala'),
        libraryFolder('al-ram', 'Random Access Memories', 'album', 'Daft Punk'),
        libraryFolder('al-folklore', 'Folklore', 'album', 'Taylor Swift'),
        libraryFolder('al-hounds', 'Hounds of Love', 'album', 'Kate Bush'),
      ],
    },
    artists: {
      title: 'Artists',
      items: [
        libraryFolder('ar-swift', 'Taylor Swift', 'artist'),
        libraryFolder('ar-mac', 'Fleetwood Mac', 'artist'),
        libraryFolder('ar-mgmt', 'MGMT', 'artist'),
      ],
    },
    radio: {
      title: 'Radio',
      items: [
        { ...libraryFolder('rd-jazz', 'Jazz FM', 'radio'), expandable: false },
        { ...libraryFolder('rd-lofi', 'Lofi Beats', 'radio'), expandable: false },
      ],
    },
    'pl-morning': {
      title: 'Morning coffee',
      items: [
        libraryTrack('t-dreams', 'Dreams', 'Fleetwood Mac'),
        libraryTrack('t-kids', 'Kids', 'MGMT'),
        libraryTrack('t-blank-space', 'Blank Space', 'Taylor Swift'),
      ],
    },
    'pl-bass': {
      title: 'BASS BOOSTED SONGS 2026 🔊 REMIXES 🔊 🔊',
      items: [
        libraryTrack('t-electric', 'Electric Feel', 'MGMT'),
        libraryTrack('t-borderline', 'Borderline', 'Tame Impala'),
      ],
    },
    'al-1989': {
      title: '1989',
      items: [
        libraryTrack('t-blank-space', 'Blank Space', 'Taylor Swift'),
        libraryTrack('t-style', 'Style', 'Taylor Swift'),
      ],
    },
    'al-rumours': {
      title: 'Rumours',
      items: [
        libraryTrack('t-dreams', 'Dreams', 'Fleetwood Mac'),
        libraryTrack('t-go-your-own-way', 'Go Your Own Way', 'Fleetwood Mac'),
      ],
    },
    'al-oracular': {
      title: 'Oracular Spectacular',
      items: [
        libraryTrack('t-kids', 'Kids', 'MGMT'),
        libraryTrack('t-electric', 'Electric Feel', 'MGMT'),
      ],
    },
    'al-currents': {
      title: 'Currents',
      items: [
        libraryTrack('t-borderline', 'Borderline', 'Tame Impala'),
        libraryTrack('t-let-it-happen', 'Let It Happen', 'Tame Impala'),
      ],
    },
    'al-ram': {
      title: 'Random Access Memories',
      items: [
        libraryTrack('t-get-lucky', 'Get Lucky', 'Daft Punk'),
        libraryTrack('t-instant-crush', 'Instant Crush', 'Daft Punk'),
      ],
    },
    'al-folklore': {
      title: 'Folklore',
      items: [
        libraryTrack('t-cardigan', 'Cardigan', 'Taylor Swift'),
        libraryTrack('t-august', 'August', 'Taylor Swift'),
      ],
    },
    'al-hounds': {
      title: 'Hounds of Love',
      items: [
        libraryTrack('t-running', 'Running Up That Hill', 'Kate Bush'),
        libraryTrack('t-cloudbusting', 'Cloudbusting', 'Kate Bush'),
      ],
    },
    'ar-swift': {
      title: 'Taylor Swift',
      items: [libraryFolder('al-1989', '1989', 'album')],
    },
    'ar-mac': { title: 'Fleetwood Mac', items: [libraryFolder('al-rumours', 'Rumours', 'album')] },
    'ar-mgmt': {
      title: 'MGMT',
      items: [libraryFolder('al-oracular', 'Oracular Spectacular', 'album')],
    },
  };
}

export interface MockIntegrationOptions {
  id?: string;

  /** What `browse` lists and `playMedia` plays. Defaults to none: the players have no library. */
  library?: MockLibrary;

  /** Entities by local id, built with the `mock*` builders. */
  entities?: Record<string, EntityInput>;
}

const clamp01 = (value: number) => Math.min(1, Math.max(0, value));

/** What a command does to an entity's state, for the kinds that have commands. */
function applyCommand(
  entity: Entity,
  name: string,
  args: Record<string, unknown> = {},
): Partial<EntityInput> {
  const num = (key: string) => {
    const value = args[key];
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new Error(`"${name}" needs a numeric "${key}"`);
    }

    return value;
  };

  switch (entity.kind) {
    case 'mediaPlayer':
      switch (name) {
        case 'play':
          return { playback: 'playing' };
        case 'pause':
          return { playback: 'paused' };
        case 'togglePlay':
          return { playback: entity.playback === 'playing' ? 'paused' : 'playing' };
        case 'setVolume':
          return { volume: clamp01(num('volume')) };
        case 'setMuted':
          return { muted: args.muted === true };
        case 'setShuffle':
          return { shuffle: args.shuffle === true };
        case 'seek':
          return {
            position: Math.max(0, num('position')),
            positionUpdatedAt: new Date().toISOString(),
          };
        default:
          return {};
      }

    case 'light':
      switch (name) {
        case 'turnOn':
          return { on: true };
        case 'turnOff':
          return { on: false };
        case 'toggle':
          return { on: !entity.on };
        case 'setBrightness': {
          const brightness = clamp01(num('brightness'));
          return brightness === 0 ? { on: false } : { on: true, brightness };
        }

        case 'setColorTemperature':
          return { on: true, color: { mode: 'temperature', kelvin: num('kelvin') } };
        case 'setColor':
          return {
            on: true,
            color: { mode: 'color', hue: num('hue'), saturation: num('saturation') },
          };
        default:
          return {};
      }

    case 'climate':
      switch (name) {
        case 'setMode':
          return { mode: args.mode as ClimateEntity['mode'] };
        case 'setTargetTemperature':
          return { targetTemperature: num('temperature') };
        case 'setPreset':
          return { preset: String(args.preset) };
        default:
          return {};
      }

    case 'switch':
      switch (name) {
        case 'turnOn':
          return { on: true };
        case 'turnOff':
          return { on: false };
        case 'toggle':
          return { on: !entity.on };
        default:
          return {};
      }

    case 'action':
      return { lastTriggered: new Date().toISOString() };
    default:
      return {};
  }
}

/** In-memory integration with fixture entities, for tests, the gallery and screenshots. */
export class MockIntegration extends BaseIntegration {
  readonly id: string;
  readonly calls: MockCall[] = [];

  /** The inputs currently stored, so `update` can patch them. */
  #inputs = new Map<string, EntityInput>();
  #library: MockLibrary;

  constructor(options: MockIntegrationOptions = {}) {
    super();
    this.id = options.id ?? 'ha';
    this.#library = options.library ?? {};
    for (const [entityId, input] of Object.entries(options.entities ?? {})) {
      this.#inputs.set(entityId, input);
      this.setEntity(entityId, input);
    }
  }

  connect(): Promise<void> {
    this.setStatus('connected');
    return Promise.resolve();
  }

  disconnect(): void {
    this.setStatus('disconnected');
  }

  /** Adds an entity, or replaces one. */
  set(entityId: string, input: EntityInput): void {
    this.#inputs.set(entityId, input);
    this.setEntity(entityId, input);
  }

  /** Patches an entity: top-level fields are replaced, `capabilities` and `media` are merged. */
  update(entityId: string, patch: Partial<EntityInput>): void {
    const current = this.#inputs.get(entityId);
    if (!current) {
      throw new UnknownEntityError(this.id, entityId);
    }

    const now = new Date().toISOString();
    const merged = { ...current, ...patch, lastUpdated: now, lastChanged: now } as Record<
      string,
      unknown
    >;

    for (const key of ['capabilities', 'media'] as const) {
      const before = (current as Record<string, unknown>)[key];
      const change = (patch as Record<string, unknown>)[key];
      if (typeof before === 'object' && typeof change === 'object' && before && change) {
        merged[key] = { ...before, ...change };
      }
    }

    this.set(entityId, merged as unknown as EntityInput);
  }

  /** Made-up history for a numeric sensor (see `mockHistory`); empty for anything else. */
  history(entityId: string, query: HistoryQuery): Promise<HistoryResult> {
    const entity = this.getEntity(entityId);
    if (!entity) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    return Promise.resolve(mockHistory(entityId, entity, query));
  }

  /** A made-up queue for a player that has one (see `mockQueue`); empty for anything else. */
  queue(entityId: string, query: QueueQuery): Promise<QueueResult> {
    const entity = this.getEntity(entityId);
    if (!entity) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    return Promise.resolve(mockQueue(entity, this.#library, query));
  }

  /** Made-up activity for a light, switch or heater (see `mockLogbook`); empty for anything else. */
  logbook(entityId: string, query: LogbookQuery): Promise<LogbookResult> {
    const entity = this.getEntity(entityId);
    if (!entity) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    return Promise.resolve(mockLogbook(entityId, entity, query));
  }

  /** Made-up forecast for a weather entity (see `mockForecast`); empty for anything else. */
  forecast(entityId: string, query: ForecastQuery): Promise<ForecastResult> {
    const entity = this.getEntity(entityId);
    if (!entity) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    return Promise.resolve(mockForecast(entity, query));
  }

  browse(entityId: string, query: BrowseQuery): Promise<BrowseResult> {
    if (!this.getEntity(entityId)) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    if (query.search !== undefined) {
      const needle = query.search.trim().toLowerCase();
      const seen = new Set<string>();
      const items = Object.values(this.#library)
        .flatMap((folder) => folder.items)
        .filter((item) => {
          const hit =
            needle !== '' &&
            (query.kind === undefined || item.kind === query.kind) &&
            `${item.title} ${item.subtitle ?? ''}`.toLowerCase().includes(needle) &&
            !seen.has(item.id);

          seen.add(item.id);
          return hit;
        });

      return Promise.resolve({ title: `Results for “${query.search}”`, items });
    }

    const folder = this.#library[query.path ?? 'root'];
    return folder
      ? Promise.resolve({ ...(folder.title ? { title: folder.title } : {}), items: folder.items })
      : Promise.reject(new Error(`Nothing at "${query.path}"`));
  }

  /** Runs something that may throw as a command does: a promise that rejects with the error. */
  #settle(action: () => void): Promise<void> {
    try {
      action();
      return Promise.resolve();
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(String(error)));
    }
  }

  /** The media player with this local id. */
  #player(entityId: string): MediaPlayerEntity {
    const input = this.#inputs.get(entityId);
    if (input?.kind !== 'mediaPlayer') {
      throw new UnknownEntityError(this.id, entityId);
    }

    return input as unknown as MediaPlayerEntity;
  }

  /** The local id of a ref to one of this integration's players. */
  #localOf(ref: string): string {
    const { integration, id } = parseEntityRef(ref);
    if (integration !== this.id) {
      throw new Error(`"${ref}" is not a player of "${this.id}"`);
    }

    return id;
  }

  #refOf = (entityId: string): string => `${this.id}:${entityId}`;

  /** Some players, as the `add` or `remove` of a command: a list of refs, or nothing. */
  #refsArg(value: unknown, key: string): string[] {
    if (value === undefined) {
      return [];
    }

    if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
      throw new Error(`"${key}" needs a list of players`);
    }

    return value as string[];
  }

  /** Takes a player out of any group it is in: it plays alone, and stops if it was following. A leader's group is
   * over, so everyone it led stops following too. */
  #detach(entityId: string): void {
    const player = this.#player(entityId);
    if (!player.group) {
      return;
    }

    const led = player.group.leader === this.#refOf(entityId);
    if (led) {
      for (const member of player.group.members) {
        this.#alone(this.#localOf(member), true);
      }

      this.#alone(entityId, false);
      return;
    }

    const leaderId = this.#localOf(player.group.leader);
    const rest = player.group.members
      .map((ref) => this.#localOf(ref))
      .filter((id) => id !== entityId);

    this.#alone(entityId, true);
    this.#form(leaderId, rest);
  }

  /** A player outside any group; one that was following stops (a leader goes on playing). */
  #alone(entityId: string, stop: boolean): void {
    const { group: _group, ...rest } = this.#player(entityId) as MediaPlayerEntity &
      Record<string, unknown>;

    const now = new Date().toISOString();
    this.set(entityId, {
      ...rest,
      ...(stop ? { playback: 'idle' } : {}),
      lastUpdated: now,
      lastChanged: now,
    } as unknown as EntityInput);
  }

  /** A leader and the players that follow it, the same group on every one, each following showing what the
   * leader plays. No members is no group. */
  #form(leaderId: string, memberIds: string[]): void {
    if (memberIds.length === 0) {
      this.#alone(leaderId, false);
      return;
    }

    const leader = this.#player(leaderId);
    const group = { leader: this.#refOf(leaderId), members: memberIds.map(this.#refOf) };
    this.update(leaderId, { group } as Partial<EntityInput>);
    for (const id of memberIds) {
      this.update(id, {
        group,
        playback: leader.playback,
        ...(leader.media ? { media: leader.media } : {}),
        ...(leader.position !== undefined ? { position: leader.position } : {}),
        ...(leader.duration !== undefined ? { duration: leader.duration } : {}),
        ...(leader.positionUpdatedAt ? { positionUpdatedAt: leader.positionUpdatedAt } : {}),
      } as Partial<EntityInput>);
    }
  }

  #setGroupMembers(entityId: string, args: Record<string, unknown> | undefined): void {
    const add = this.#refsArg(args?.add, 'add');
    const remove = this.#refsArg(args?.remove, 'remove');
    const leader = this.#player(entityId);
    if (add.length === 0 && remove.length === 0) {
      return;
    }

    if (!leader.capabilities.group) {
      throw new Error(`"${entityId}" cannot be grouped`);
    }

    const own = this.#refOf(entityId);
    if (leader.group && leader.group.leader !== own) {
      throw new Error(`"${entityId}" follows ${leader.group.leader}: ask the leader`);
    }

    let members = (leader.group?.members ?? []).map((ref) => this.#localOf(ref));
    for (const ref of remove) {
      const id = this.#localOf(ref);
      if (members.includes(id)) {
        members = members.filter((other) => other !== id);
        this.#alone(id, true);
      }
    }

    for (const ref of add) {
      const id = this.#localOf(ref);
      if (id === entityId || members.includes(id)) {
        continue;
      }

      const other = this.#player(id);
      if (
        !other.capabilities.group ||
        (leader.groupable && !leader.groupable.includes(ref as never))
      ) {
        throw new Error(`"${id}" cannot be grouped with "${entityId}"`);
      }

      this.#detach(id);
      members.push(id);
    }

    this.#form(entityId, members);
  }

  #leaveGroup(entityId: string): void {
    this.#detach(entityId);
  }

  /** A follower becomes the leader of its group: the player that led stops and leaves it, and the rest follow this one. */
  #takeOverGroup(entityId: string): void {
    const player = this.#player(entityId);
    const own = this.#refOf(entityId);
    if (!player.group || player.group.leader === own) {
      return;
    }

    const formerLeader = this.#localOf(player.group.leader);
    const rest = player.group.members
      .map((ref) => this.#localOf(ref))
      .filter((id) => id !== entityId);

    this.#alone(formerLeader, true);
    this.#form(entityId, rest);
  }

  #playMedia(entityId: string, item: unknown, mode?: unknown): Promise<void> {
    const found = Object.values(this.#library)
      .flatMap((folder) => folder.items)
      .find((candidate) => candidate.id === item);

    const current = this.#inputs.get(entityId);
    if (!found?.playable || !current || current.kind !== 'mediaPlayer') {
      return Promise.reject(new Error(`Cannot play "${String(item)}"`));
    }

    // Queued for later, not played: what is playing carries on.
    if (mode === 'next' || mode === 'add') {
      return Promise.resolve();
    }

    const now = new Date().toISOString();
    this.set(entityId, {
      ...current,
      playback: 'playing',
      media: {
        title: found.title,
        ...(found.subtitle ? { artist: found.subtitle } : {}),
        ...(found.artworkUrl ? { artworkUrl: found.artworkUrl } : {}),
      },
      position: 0,
      duration: 215,
      positionUpdatedAt: now,
      lastUpdated: now,
      lastChanged: now,
    });

    return Promise.resolve();
  }

  command(entityId: string, name: string, args?: Record<string, unknown>): Promise<void> {
    this.calls.push({ entityId, command: name, ...(args ? { args } : {}) });
    const entity = this.getEntity(entityId);
    if (!entity) {
      return Promise.reject(new UnknownEntityError(this.id, entityId));
    }

    if (!isCommandOf(entity.kind, name)) {
      return Promise.reject(new Error(`A ${entity.kind} has no "${name}" command`));
    }

    if (entity.kind === 'mediaPlayer' && name === 'playMedia') {
      return this.#playMedia(entityId, args?.item, args?.mode);
    }

    if (entity.kind === 'mediaPlayer' && name === 'setGroupMembers') {
      return this.#settle(() => this.#setGroupMembers(entityId, args));
    }

    if (entity.kind === 'mediaPlayer' && name === 'leaveGroup') {
      return this.#settle(() => this.#leaveGroup(entityId));
    }

    if (entity.kind === 'mediaPlayer' && name === 'takeOverGroup') {
      return this.#settle(() => this.#takeOverGroup(entityId));
    }

    try {
      this.update(entityId, applyCommand(entity, name, args));
    } catch (error) {
      return Promise.reject(error instanceof Error ? error : new Error(String(error)));
    }

    return Promise.resolve();
  }
}

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { UnknownEntityError } from '@hashsome/core';
import { MusicAssistantIntegration } from '../index.ts';

// Applies to every test in this file (vitest hooks aren't position-sensitive): fake timers so the
// reconnect-backoff tests can fast-forward, and `flush()` below drives both real microtasks and
// pending fake timers so the rest of the tests don't need real setTimeout(0) delays.
beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

const flush = () => vi.advanceTimersByTimeAsync(0);

class FakeSocket extends EventTarget {
  static instances: FakeSocket[] = [];
  readyState = 0;
  sent: { message_id: string; command: string; args?: Record<string, unknown> }[] = [];
  constructor(public url: string) {
    super();
    FakeSocket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {
    this.readyState = 3;
    this.dispatchEvent(new Event('close'));
  }
  open() {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  receive(message: unknown) {
    this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(message) }));
  }
}

const player = (overrides: Record<string, unknown> = {}) => ({
  player_id: 'kitchen_speaker',
  available: true,
  playback_state: 'playing',
  volume_level: 40,
  volume_muted: false,
  current_media: {
    title: 'Blank Space',
    artist: 'More More',
    album: null,
    image_url: 'http://x/art.jpg',
  },
  ...overrides,
});

function make(options: { reconnectMinMs?: number } = {}) {
  FakeSocket.instances = [];
  const ma = new MusicAssistantIntegration({
    url: 'http://mass.local:8095',
    token: 'tok',
    createSocket: (url) => new FakeSocket(url) as unknown as WebSocket,
    ...options,
  });

  return { ma, socket: () => FakeSocket.instances.at(-1)! };
}

/** Drives an already-opening socket through open -> auth ack -> players/all response. Used both
 * for the initial `connect()` and for a socket opened internally by auto-reconnect (which never
 * calls `connect()` again — see its doc comment). */
async function driveHandshake(socket: () => FakeSocket, players: unknown[] = [player()]) {
  await flush();
  socket().open();
  await flush();
  socket().receive({ message_id: socket().sent[0]!.message_id, result: { authenticated: true } });
  await flush();
  socket().receive({ message_id: socket().sent[1]!.message_id, result: players });
  // `connect()`'s own returned promise gives the happy path a natural point to await full
  // settlement; the internal reconnect path has no such promise (it's fire-and-forget), so this
  // flush is what lets its pending status-setting continuation actually run before we return.
  await flush();
}

async function connect(
  ma: MusicAssistantIntegration,
  socket: () => FakeSocket,
  players: unknown[] = [player()],
) {
  const done = ma.connect();
  await driveHandshake(socket, players);
  await done;
}

test('connects to <url>/ws, authenticates, then fetches players', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  expect(socket().url).toBe('ws://mass.local:8095/ws');
  expect(socket().sent[0]).toMatchObject({ command: 'auth', args: { token: 'tok' } });
  expect(socket().sent[1]).toMatchObject({ command: 'players/all' });
  expect(ma.status).toBe('connected');
});

test('maps a player to a native mediaPlayer entity', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ display_name: 'Kitchen Speaker' })]);
  expect(ma.getEntity('kitchen_speaker')).toEqual({
    ref: 'ma:kitchen_speaker',
    kind: 'mediaPlayer',
    name: 'Kitchen Speaker',
    availability: 'ready',
    playback: 'playing',
    media: { title: 'Blank Space', artist: 'More More', artworkUrl: 'http://x/art.jpg' },
    volume: 0.4,
    muted: false,
    capabilities: {
      volume: true,
      mute: true,
      next: true,
      previous: true,
      browse: true,
      search: true,
      seek: true,
      shuffle: true,
      queue: true,
      transfer: false,
      group: false,
    },
  });
});

test('the name falls back to the player id, and players without volume say so', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ volume_level: null, volume_muted: null })]);
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    name: 'kitchen_speaker',
    muted: false,
    capabilities: { volume: false, mute: false },
  });

  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('volume');
});

test('an unavailable player is unavailable and off', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ available: false })]);
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    availability: 'unavailable',
    playback: 'off',
  });
});

test('player_updated and player_removed events update the store live', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  socket().receive({
    event: 'player_updated',
    object_id: 'kitchen_speaker',
    data: player({ playback_state: 'paused' }),
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ playback: 'paused' });
  socket().receive({ event: 'player_removed', object_id: 'kitchen_speaker' });
  expect(ma.getEntity('kitchen_speaker')).toBeUndefined();
});

test('subscribing to a player that does not exist throws', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  expect(() => ma.subscribe('nope', () => {})).toThrow(UnknownEntityError);
});

test('transport commands map to players/cmd/* with the player id', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const expected: Record<string, string> = {
    play: 'players/cmd/play',
    pause: 'players/cmd/pause',
    togglePlay: 'players/cmd/play_pause',
    next: 'players/cmd/next',
    previous: 'players/cmd/previous',
  };

  for (const [name, command] of Object.entries(expected)) {
    const call = ma.command('kitchen_speaker', name);
    await flush();
    expect(socket().sent.at(-1)).toMatchObject({ command, args: { player_id: 'kitchen_speaker' } });
    socket().receive({ message_id: socket().sent.at(-1)!.message_id, result: null });
    await expect(call).resolves.toBeUndefined();
  }
});

test('setVolume converts 0..1 to 0..100 and setMuted forwards muted', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  void ma.command('kitchen_speaker', 'setVolume', { volume: 0.7 });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'players/cmd/volume_set',
    args: { player_id: 'kitchen_speaker', volume_level: 70 },
  });

  void ma.command('kitchen_speaker', 'setMuted', { muted: true });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'players/cmd/volume_mute',
    args: { player_id: 'kitchen_speaker', muted: true },
  });
});

test('commands reject for an unknown player, an unknown command and bad arguments', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  await expect(ma.command('nope', 'play')).rejects.toThrow(UnknownEntityError);
  await expect(ma.command('kitchen_speaker', 'toggle')).rejects.toThrow(/no "toggle" command/);
  await expect(ma.command('kitchen_speaker', 'setVolume', { volume: 'loud' })).rejects.toThrow(
    /numeric volume/,
  );

  await expect(ma.command('kitchen_speaker', 'setMuted', {})).rejects.toThrow(/boolean/);
});

test('an auth error rejects connect() and sets status to error', async () => {
  const { ma, socket } = make();
  const done = ma.connect();
  await flush();
  socket().open();
  await flush();
  socket().receive({
    message_id: socket().sent[0]!.message_id,
    error_code: 401,
    details: 'bad token',
  });

  await expect(done).rejects.toThrow('bad token');
  expect(ma.status).toBe('error');
});

test('reconnects with backoff after the connection drops, and resumes serving state', async () => {
  const { ma, socket } = make({ reconnectMinMs: 100 });
  await connect(ma, socket);
  socket().close();
  expect(ma.status).toBe('disconnected');
  await vi.advanceTimersByTimeAsync(100);
  expect(FakeSocket.instances).toHaveLength(2);
  await driveHandshake(socket, [player({ playback_state: 'paused' })]);
  expect(ma.status).toBe('connected');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ playback: 'paused' });
});

test('disconnect() stops pending reconnect attempts', async () => {
  const { ma, socket } = make({ reconnectMinMs: 100 });
  await connect(ma, socket);
  socket().close();
  ma.disconnect();
  await vi.advanceTimersByTimeAsync(10_000);
  expect(FakeSocket.instances).toHaveLength(1);
});

test('position and duration come from the elapsed time Music Assistant reports', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [
    player({
      elapsed_time: 64,
      elapsed_time_last_updated: 1_767_225_600,
      current_media: { title: 'Blank Space', duration: 231 },
    }),
  ]);

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    position: 64,
    duration: 231,
    positionUpdatedAt: '2026-01-01T00:00:00.000Z',
  });
});

test('seek and playMedia go to the player’s queue, with the mode as the queue option', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  void ma.command('kitchen_speaker', 'seek', { position: 12.4 });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/seek',
    args: { queue_id: 'kitchen_speaker', position: 12 },
  });

  void ma.command('kitchen_speaker', 'playMedia', { item: 'library://album/12', mode: 'replace' });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/play_media',
    args: { queue_id: 'kitchen_speaker', media: 'library://album/12', option: 'replace' },
  });

  await expect(ma.command('kitchen_speaker', 'playMedia', { item: '' })).rejects.toThrow(/item/);
  await expect(
    ma.command('kitchen_speaker', 'playMedia', { item: 'x://a/1', mode: 'loud' }),
  ).rejects.toThrow(/mode/);
});

/** Answers the next request the integration sent, the way the server would. */
const answer = async (socket: () => FakeSocket, result: unknown) => {
  await flush();
  socket().receive({ message_id: socket().sent.at(-1)!.message_id, result });
  await flush();
};

test('the playlist or album the queue was filled from is on the player, and goes when the queue is filled otherwise', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('source');

  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: {
      queue_id: 'kitchen_speaker',
      state: 'playing',
      sources: [{ media_type: 'playlist', name: 'Daily Mix 2' }],
    },
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    source: { title: 'Daily Mix 2', kind: 'playlist' },
  });

  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: { queue_id: 'kitchen_speaker', state: 'playing', sources: [] },
  });

  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('source');
});

test('playing something turns on autoplay on its queue, so it goes on with similar tracks, unless that is switched off', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  void ma.command('kitchen_speaker', 'playMedia', { item: 'library://track/1' });
  await flush();
  expect(
    socket()
      .sent.slice(-2)
      .map(({ command, args }) => [command, args]),
  ).toEqual([
    ['player_queues/autoplay', { queue_id: 'kitchen_speaker', autoplay_enabled: true }],
    [
      'player_queues/play_media',
      { queue_id: 'kitchen_speaker', media: 'library://track/1', option: 'play' },
    ],
  ]);

  // Putting a track in a queue that is already going does not touch the setting.
  void ma.command('kitchen_speaker', 'playMedia', { item: 'library://track/2', mode: 'add' });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({ command: 'player_queues/play_media' });
  expect(socket().sent.at(-2)).toMatchObject({ command: 'player_queues/play_media' });

  const off = new MusicAssistantIntegration({
    url: 'http://mass.local:8095',
    token: 'tok',
    autoplay: false,
    createSocket: (url) => new FakeSocket(url) as unknown as WebSocket,
  });

  await connect(off, () => FakeSocket.instances.at(-1)!);
  void off.command('kitchen_speaker', 'playMedia', { item: 'library://track/1' });
  await flush();
  expect(
    FakeSocket.instances.at(-1)!.sent.filter((sent) => sent.command === 'player_queues/autoplay'),
  ).toHaveLength(0);
});

test('the picture of what is playing is addressed through the runtime when Music Assistant serves it, and left alone when it does not', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [
    player({
      player_id: 'own',
      current_media: { title: 'A', image_url: 'http://10.4.2.2/imageproxy/abc?size=512' },
    }),
    player({
      player_id: 'cdn',
      current_media: { title: 'B', image_url: 'https://i.scdn.co/image/xyz' },
    }),
  ]);

  expect(ma.getEntity('own')).toMatchObject({
    media: { artworkUrl: '/_hashsome/asset/ma?path=%2Fimageproxy%2Fabc%3Fsize%3D256' },
  });

  expect(ma.getEntity('cdn')).toMatchObject({
    media: { artworkUrl: 'https://i.scdn.co/image/xyz' },
  });
});

test('a picture is fetched at the pixels Music Assistant calls small, medium and large', async () => {
  const spy = vi.fn<(url: URL, init?: RequestInit) => Promise<Response>>(() =>
    Promise.resolve(new Response('img')),
  );

  vi.stubGlobal('fetch', spy);
  const { ma } = make();
  const sizeOf = async (size?: 'small' | 'medium' | 'large') => {
    await ma.fetchAsset('/imageproxy/abc?size=512&fmt=jpg', size === undefined ? {} : { size });

    return new URL(String(spy.mock.calls.at(-1)?.[0])).searchParams.get('size');
  };

  // None said: the size in the path.
  expect(await sizeOf()).toBe('512');
  expect(await sizeOf('small')).toBe('80');
  expect(await sizeOf('medium')).toBe('256');
  expect(await sizeOf('large')).toBe('512');
  vi.unstubAllGlobals();
});

test('a picture is fetched from Music Assistant with the token, and nothing else is', async () => {
  const spy = vi.fn<(url: URL, init?: RequestInit) => Promise<Response>>(() =>
    Promise.resolve(new Response('img')),
  );

  vi.stubGlobal('fetch', spy);
  const { ma } = make();
  await ma.fetchAsset('/imageproxy/abc?size=512');
  expect(String(spy.mock.calls[0]?.[0])).toBe('http://mass.local:8095/imageproxy/abc?size=512');
  expect(spy.mock.calls[0]?.[1]).toMatchObject({ headers: { Authorization: 'Bearer tok' } });
  for (const path of [
    '/api/players',
    'imageproxy/x',
    '//evil.test/imageproxy/x',
    'http://evil.test/imageproxy/x',
  ]) {
    await expect(ma.fetchAsset(path)).rejects.toThrow('Not a picture of Music Assistant');
  }

  expect(spy).toHaveBeenCalledTimes(1);
  vi.unstubAllGlobals();
});

test('browse lists the shelves, then what is in a shelf, an album and an artist', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);

  const root = await ma.browse('kitchen_speaker', {});
  expect(root.items.map((item) => item.title)).toEqual([
    'Recently played',
    'Playlists',
    'Albums',
    'Artists',
    'Radio',
  ]);

  expect(root.items.every((item) => item.expandable && !item.playable)).toBe(true);

  const albums = ma.browse('kitchen_speaker', { path: 'shelf:albums' });
  await answer(socket, [
    {
      uri: 'library://album/12',
      name: '1989',
      media_type: 'album',
      artists: [{ name: 'Taylor Swift' }],
      image: { path: 'https://cdn.example/1989.jpg', remotely_accessible: true },
    },
    { name: 'no uri, dropped' },
  ]);

  expect(socket().sent.at(-1)).toMatchObject({ command: 'music/albums/library_items' });
  expect(await albums).toEqual({
    title: 'Albums',
    items: [
      {
        id: 'library://album/12',
        title: '1989',
        subtitle: 'Taylor Swift',
        kind: 'album',
        playable: true,
        expandable: true,
        artworkUrl: 'https://cdn.example/1989.jpg',
      },
    ],
  });

  const tracks = ma.browse('kitchen_speaker', { path: 'library://album/12' });
  await answer(socket, [
    { uri: 'library://track/3', name: 'Style', media_type: 'track', artist_str: 'Taylor Swift' },
  ]);

  expect(socket().sent.at(-1)).toMatchObject({
    command: 'music/albums/album_tracks',
    args: { item_id: '12', provider_instance_id_or_domain: 'library' },
  });

  expect((await tracks).items[0]).toMatchObject({
    kind: 'track',
    playable: true,
    expandable: false,
  });

  const albumsOfArtist = ma.browse('kitchen_speaker', { path: 'spotify://artist/abc' });
  await answer(socket, []);
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'music/artists/artist_albums',
    args: { item_id: 'abc', provider_instance_id_or_domain: 'spotify' },
  });

  expect((await albumsOfArtist).items).toEqual([]);
});

test('private pictures are not passed on, and browse validates what it is asked', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const shelf = ma.browse('kitchen_speaker', { path: 'shelf:playlists' });
  await answer(socket, [
    {
      uri: 'library://playlist/1',
      name: 'Morning',
      media_type: 'playlist',
      image: { path: 'private/path.jpg', remotely_accessible: false },
    },
  ]);

  expect((await shelf).items[0]).not.toHaveProperty('artworkUrl');

  await expect(ma.browse('nobody', {})).rejects.toBeInstanceOf(UnknownEntityError);
  await expect(ma.browse('kitchen_speaker', { path: 'not a uri' })).rejects.toThrow(/media item/);
  await expect(ma.browse('kitchen_speaker', { path: 'library://track/1' })).rejects.toThrow(
    /nothing inside/,
  );
});

test('search asks Music Assistant once and lists albums, artists, tracks, playlists and radio', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const found = ma.browse('kitchen_speaker', { search: 'swift' });
  await answer(socket, {
    albums: [{ uri: 'library://album/12', name: '1989', media_type: 'album' }],
    artists: [{ uri: 'library://artist/1', name: 'Taylor Swift', media_type: 'artist' }],
    tracks: [{ uri: 'library://track/3', name: 'Style', media_type: 'track' }],
    playlists: [],
    radio: [],
  });

  expect(socket().sent.at(-1)).toMatchObject({
    command: 'music/search',
    args: { search_query: 'swift' },
  });

  expect((await found).items.map((item) => item.kind)).toEqual(['album', 'artist', 'track']);
});

test('a search narrowed to one kind asks Music Assistant for only that kind, and for far more of it', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const all = ma.browse('kitchen_speaker', { search: 'swift' });
  await answer(socket, { albums: [], artists: [], tracks: [], playlists: [], radio: [] });
  await all;
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'music/search',
    args: { media_types: ['album', 'artist', 'track', 'playlist', 'radio'], limit: 8 },
  });

  const some = ma.browse('kitchen_speaker', { search: 'swift', kind: 'playlist' });
  await answer(socket, {
    playlists: [{ uri: 'library://playlist/5', name: 'Swift mix', media_type: 'playlist' }],
  });

  expect(socket().sent.at(-1)).toMatchObject({
    command: 'music/search',
    args: { search_query: 'swift', media_types: ['playlist'], limit: 50 },
  });

  expect((await some).items.map((item) => item.kind)).toEqual(['playlist']);

  // A kind that is not something Music Assistant has is nothing, without asking it.
  const sent = socket().sent.length;
  expect((await ma.browse('kitchen_speaker', { search: 'swift', kind: 'folder' })).items).toEqual(
    [],
  );

  expect(socket().sent).toHaveLength(sent);
});

test('the position is the queue’s, since a resumed stream starts the player’s own counter from 0', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [
    player({
      elapsed_time: 0.3,
      elapsed_time_last_updated: 1_767_225_600,
      playback_state: 'playing',
    }),
  ]);

  // Before anything is known about the queue, the player's own counter is all there is.
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 0.3 });

  // Music Assistant resumed the track at 23 s in a new stream: the player says 0, the queue 23.
  vi.setSystemTime(new Date('2026-01-01T00:01:00.000Z'));
  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: { queue_id: 'kitchen_speaker', state: 'playing', elapsed_time: 23 },
  });

  // Stamped when it arrived: Music Assistant's own clock is not the reader's.
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    position: 23,
    positionUpdatedAt: '2026-01-01T00:01:00.000Z',
  });

  // The player's next update, back at 0, does not take the position away from the queue.
  socket().receive({
    event: 'player_updated',
    object_id: 'kitchen_speaker',
    data: player({
      elapsed_time: 0.4,
      elapsed_time_last_updated: 1_767_225_661,
      playback_state: 'playing',
    }),
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 23 });

  // And the queue counting on moves it.
  vi.setSystemTime(new Date('2026-01-01T00:01:01.000Z'));
  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: { queue_id: 'kitchen_speaker', state: 'playing', elapsed_time: 24 },
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 24 });
});

test('pause and resume, as Music Assistant reports them, never move the position behind the pause', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ playback_state: 'idle' })]);
  const at = (seconds: number) => vi.setSystemTime(new Date(Date.UTC(2026, 0, 1) + seconds * 1000));
  const queue = (data: Record<string, unknown>) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: { queue_id: 'kitchen_speaker', current_item: { queue_item_id: 'track-1' }, ...data },
    });

  const playback = (state: string) =>
    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen_speaker',
      data: player({ playback_state: state }),
    });

  const seen: number[] = [];
  ma.subscribe('kitchen_speaker', (entity) =>
    seen.push((entity as { position?: number } | undefined)?.position ?? Number.NaN),
  );

  // Playback starts. Music Assistant then says nothing about the queue for 16 seconds.
  at(62);
  playback('playing');
  queue({ state: 'idle', elapsed_time: 0.5, resume_pos: 0 });
  queue({ state: 'playing', elapsed_time: 0.5, resume_pos: 0 });

  // The pause: the player goes idle first, then the queue. The position is worked out from how long
  // it played, and the resume spot arriving later (after an old one) changes nothing.
  at(78.2);
  playback('idle');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: expect.closeTo(16.7, 2) });
  queue({ state: 'idle', elapsed_time: 3, resume_pos: 0.5 });
  at(78.8);
  queue({ state: 'idle', elapsed_time: 3, resume_pos: 16 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: expect.closeTo(16.7, 2) });

  // The resume: Music Assistant clears the resume spot and plays. Until the queue says where, it
  // is shown from where it was held, and the stream starting a moment before that is not a step back.
  at(87.6);
  queue({ state: 'idle', elapsed_time: 0, resume_pos: 0 });
  at(88);
  playback('playing');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: expect.closeTo(16.7, 2) });
  queue({ state: 'playing', elapsed_time: 16.2, resume_pos: 0 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: expect.closeTo(16.7, 2) });

  // Once it has settled the queue's own counter is followed.
  at(100);
  queue({ state: 'playing', elapsed_time: 28.5 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 28.5 });

  // From the pause on, nothing shown is behind where it was paused.
  const afterPause = seen.slice(seen.findIndex((spot) => Math.abs(spot - 16.7) < 0.01));
  expect(afterPause.every((spot) => spot >= 16.69)).toBe(true);
});

test('the next track starts at its own start, not at the last track’s resume spot', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ playback_state: 'playing' })]);
  const queue = (uri: string, data: Record<string, unknown>) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        current_item: { queue_item_id: uri, media_item: { uri } },
        ...data,
      },
    });

  const playback = (state: string) =>
    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen_speaker',
      data: player({ playback_state: state }),
    });

  queue('track-1', { state: 'playing', elapsed_time: 0.8, resume_pos: 0 });
  playback('idle');
  queue('track-1', { state: 'idle', elapsed_time: 10, resume_pos: 13 });

  // Next: the new track, still carrying the last one's resume spot, and not yet playing.
  queue('track-2', { state: 'idle', elapsed_time: 0, resume_pos: 13 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 0 });

  // Then it plays, from its own start.
  playback('playing');
  queue('track-2', { state: 'playing', elapsed_time: 0, resume_pos: 0 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 0 });
});

test('a resumed queue is the same track even if its id in the queue is not', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ playback_state: 'idle' })]);
  const queue = (id: string, data: Record<string, unknown>) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        current_item: { queue_item_id: id, media_item: { uri: 'spotify://track/1' } },
        ...data,
      },
    });

  queue('first', { elapsed_time: 18, resume_pos: 30 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 30 });
  queue('second', { elapsed_time: 0, resume_pos: 0 });
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 30 });
});

test('a stopped player shows no position until its queue has said where it is', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [
    player({ playback_state: 'idle', elapsed_time: 0, elapsed_time_last_updated: 1_767_225_600 }),
  ]);

  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('position');
});

test('a track the queue moves to starts at its own position, not where the last was paused', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ playback_state: 'playing' })]);
  const queue = (item: string, elapsed: number) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        current_item: { queue_item_id: item, name: item },
        elapsed_time: elapsed,
      },
    });

  queue('track-1', 90);
  socket().receive({
    event: 'player_updated',
    object_id: 'kitchen_speaker',
    data: player({ playback_state: 'idle' }),
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: expect.any(Number) });
  queue('track-2', 0);
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ position: 0 });
});

test('a queue without a stamp for its elapsed time is taken as of when it arrived', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const before = Date.now();
  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: { queue_id: 'kitchen_speaker', elapsed_time: 10 },
  });

  const entity = ma.getEntity('kitchen_speaker');
  expect(entity).toMatchObject({ position: 10 });
  expect(
    Date.parse((entity as { positionUpdatedAt: string }).positionUpdatedAt),
  ).toBeGreaterThanOrEqual(before - 1000);
});

test('shuffle is read from the queues, kept up to date by queue events, and set on the queue', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('shuffle');

  // The queues are asked for once the players are known.
  expect(socket().sent.at(-1)).toMatchObject({ command: 'player_queues/all' });
  socket().receive({
    message_id: socket().sent.at(-1)!.message_id,
    result: [{ queue_id: 'kitchen_speaker', shuffle_enabled: true }],
  });

  await flush();
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({
    shuffle: true,
    capabilities: { shuffle: true },
  });

  socket().receive({
    event: 'queue_updated',
    object_id: 'kitchen_speaker',
    data: { queue_id: 'kitchen_speaker', shuffle_enabled: false },
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ shuffle: false });

  // A later player update keeps the queue's setting instead of losing it.
  socket().receive({
    event: 'player_updated',
    object_id: 'kitchen_speaker',
    data: player({ volume_level: 55 }),
  });

  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ shuffle: false, volume: 0.55 });

  void ma.command('kitchen_speaker', 'setShuffle', { shuffle: true });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/shuffle',
    args: { queue_id: 'kitchen_speaker', shuffle_enabled: true },
  });

  await expect(ma.command('kitchen_speaker', 'setShuffle', { shuffle: 'yes' })).rejects.toThrow(
    /boolean/,
  );
});

test('a server that does not answer for queues leaves shuffle unreported and does not hold up connect', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  expect(ma.status).toBe('connected');
  expect(ma.getEntity('kitchen_speaker')).not.toHaveProperty('shuffle');
});

test('playMedia with a context queues the whole album or playlist and starts at the track, optionally shuffled', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  void ma.command('kitchen_speaker', 'playMedia', {
    item: 'library://track/7',
    context: 'library://album/12',
    mode: 'replace',
    shuffle: false,
  });

  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/play_media',
    args: {
      queue_id: 'kitchen_speaker',
      media: 'library://album/12',
      start_item: 'library://track/7',
      option: 'replace',
      shuffle: false,
    },
  });

  // Without a context the track is the media, with no start item.
  void ma.command('kitchen_speaker', 'playMedia', { item: 'library://track/7', mode: 'add' });
  await flush();
  const sent = socket().sent.at(-1)!;
  expect(sent.args).toMatchObject({ media: 'library://track/7', option: 'add' });
  expect(sent.args).not.toHaveProperty('start_item');
  expect(sent.args).not.toHaveProperty('shuffle');

  await expect(
    ma.command('kitchen_speaker', 'playMedia', { item: 'x://a/1', context: '' }),
  ).rejects.toThrow(/context/);

  await expect(
    ma.command('kitchen_speaker', 'playMedia', { item: 'x://a/1', shuffle: 'yes' }),
  ).rejects.toThrow(/shuffle/);
});

describe('grouping players', () => {
  const grouped = (overrides: Record<string, unknown> = {}) => ({
    supported_features: ['set_members'],
    ...overrides,
  });

  const two = [
    player({
      player_id: 'porch',
      display_name: 'Porch',
      ...grouped({ can_group_with: ['kitchen'] }),
      group_childs: ['porch', 'kitchen'],
    }),
    player({
      player_id: 'kitchen',
      display_name: 'Kitchen',
      playback_state: 'idle',
      current_media: null,
      volume_level: 25,
      ...grouped({ can_group_with: ['porch', 'office'] }),
      synced_to: 'porch',
    }),
  ];

  test('a player that can be grouped says with whom, and the group it is in on both sides', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, two);
    expect(ma.getEntity('porch')).toMatchObject({
      group: { leader: 'ma:porch', members: ['ma:kitchen'] },
      groupable: ['ma:kitchen'],
      capabilities: { group: true },
    });

    expect(ma.getEntity('kitchen')).toMatchObject({
      group: { leader: 'ma:porch', members: ['ma:kitchen'] },
      groupable: ['ma:porch', 'ma:office'],
    });
  });

  test('a player that does not take the feature is not groupable, and has nothing to offer', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, [player({ player_id: 'solo' })]);
    expect(ma.getEntity('solo')).not.toHaveProperty('group');
    expect(ma.getEntity('solo')).not.toHaveProperty('groupable');
    expect(ma.getEntity('solo')).toMatchObject({ capabilities: { group: false } });
  });

  test('a follower shows what its leader plays, and keeps its own volume and name', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, two);
    expect(ma.getEntity('kitchen')).toMatchObject({
      name: 'Kitchen',
      playback: 'playing',
      media: { title: 'Blank Space' },
      volume: 0.25,
    });
  });

  test('a follower, which Music Assistant lists nobody for, can still be grouped with what its leader can', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, [two[0]!, { ...two[1]!, can_group_with: [] }]);

    expect(ma.getEntity('kitchen')).toMatchObject({ groupable: ['ma:porch'] });
  });

  test('a player its leader lists follows it even before it says so itself', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, [two[0]!, { ...two[1]!, synced_to: null }]);
    expect(ma.getEntity('kitchen')).toMatchObject({
      group: { leader: 'ma:porch', members: ['ma:kitchen'] },
      playback: 'playing',
      media: { title: 'Blank Space' },
    });
  });

  test('when the leader changes, its followers do too', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, two);
    socket().receive({
      event: 'player_updated',
      object_id: 'porch',
      data: {
        ...two[0],
        playback_state: 'paused',
        current_media: { title: 'Dreams', artist: 'FM' },
      },
    });

    expect(ma.getEntity('kitchen')).toMatchObject({
      playback: 'paused',
      media: { title: 'Dreams' },
    });

    // The leader lets the follower go: it is on its own again, and shows its own state.
    socket().receive({
      event: 'player_updated',
      object_id: 'porch',
      data: { ...two[0], group_childs: ['porch'] },
    });

    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen',
      data: { ...two[1], synced_to: null },
    });

    expect(ma.getEntity('kitchen')).not.toHaveProperty('group');
    expect(ma.getEntity('porch')).not.toHaveProperty('group');
    expect(ma.getEntity('kitchen')).toMatchObject({ playback: 'idle' });
  });

  test('adding and removing players asks Music Assistant to set the members of this one', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, [
      ...two,
      player({ player_id: 'office', ...grouped({ can_group_with: ['porch'] }) }),
    ]);

    void ma.command('porch', 'setGroupMembers', { add: ['ma:office'], remove: ['ma:kitchen'] });
    await flush();
    expect(socket().sent.at(-1)).toMatchObject({
      command: 'players/cmd/set_members',
      args: {
        target_player: 'porch',
        player_ids_to_add: ['office'],
        player_ids_to_remove: ['kitchen'],
      },
    });

    // Only what is given is sent, and an empty change sends nothing.
    void ma.command('porch', 'setGroupMembers', { add: ['ma:office'] });
    await flush();
    expect(socket().sent.at(-1)?.args).toEqual({
      target_player: 'porch',
      player_ids_to_add: ['office'],
    });

    const sent = socket().sent.length;
    await ma.command('porch', 'setGroupMembers', { add: [] });
    expect(socket().sent).toHaveLength(sent);
  });

  test('leaving takes a follower out of its group, and a leader’s followers out of it', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, two);
    void ma.command('kitchen', 'leaveGroup');
    await flush();
    expect(socket().sent.at(-1)).toMatchObject({
      command: 'players/cmd/ungroup',
      args: { player_id: 'kitchen' },
    });

    void ma.command('porch', 'leaveGroup');
    await flush();
    expect(socket().sent.at(-1)).toMatchObject({
      command: 'players/cmd/set_members',
      args: { target_player: 'porch', player_ids_to_remove: ['kitchen'] },
    });
  });

  test('taking over moves the stream to a follower, puts the others behind it and stops the old leader', async () => {
    const { ma, socket } = make();
    const office = player({
      player_id: 'office',
      display_name: 'Office',
      ...grouped(),
      synced_to: 'porch',
    });

    await connect(ma, socket, [
      { ...two[0]!, group_childs: ['porch', 'kitchen', 'office'] },
      two[1]!,
      office,
    ]);

    const done = ma.command('kitchen', 'takeOverGroup');
    // Each step waits for the one before it to be answered.
    for (let step = 0; step < 4; step++) {
      await answer(socket, null);
    }

    await done;
    expect(
      socket()
        .sent.slice(-4)
        .map(({ command, args }) => [command, args]),
    ).toEqual([
      ['players/cmd/ungroup', { player_id: 'kitchen' }],
      [
        'player_queues/transfer',
        { source_queue_id: 'porch', target_queue_id: 'kitchen', auto_play: true },
      ],
      ['players/cmd/set_members', { target_player: 'kitchen', player_ids_to_add: ['office'] }],
      ['players/cmd/stop', { player_id: 'porch' }],
    ]);

    // A leader, or a player in no group, has nothing to take over.
    const sent = socket().sent.length;
    await ma.command('porch', 'takeOverGroup');
    expect(socket().sent).toHaveLength(sent);
  });

  test('players of another integration, unknown players and a malformed list are refused', async () => {
    const { ma, socket } = make();
    await connect(ma, socket, two);
    const refused: [Record<string, unknown>, RegExp][] = [
      [{ add: ['sonos:kitchen'] }, /not a player of/],
      [{ add: ['ma:nobody'] }, /not a player of/],
      [{ remove: 'ma:kitchen' }, /list of players/],
      [{ add: [4] }, /list of players/],
    ];

    for (const [args, reason] of refused) {
      await expect(ma.command('porch', 'setGroupMembers', args)).rejects.toThrow(reason);
    }
  });
});

test('the queue commands jump to an item or take one out', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  void ma.command('kitchen_speaker', 'playQueueItem', { item: 'qi-9' });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/play_index',
    args: { queue_id: 'kitchen_speaker', index: 'qi-9' },
  });

  void ma.command('kitchen_speaker', 'removeQueueItem', { item: 'qi-9' });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/delete_item',
    args: { queue_id: 'kitchen_speaker', item_id_or_index: 'qi-9' },
  });

  await expect(ma.command('kitchen_speaker', 'playQueueItem', {})).rejects.toThrow(/queue item/);
});

test('moveQueueItem shifts a track by places, and refuses a shift that is not a move', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);

  void ma.command('kitchen_speaker', 'moveQueueItem', { item: 'qi-9', shift: -2 });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/move_item',
    args: { queue_id: 'kitchen_speaker', queue_item_id: 'qi-9', pos_shift: -2 },
  });

  // Music Assistant would take 0 for "play next".

  await expect(
    ma.command('kitchen_speaker', 'moveQueueItem', { item: 'qi-9', shift: 0 }),
  ).rejects.toThrow(/shift/);

  await expect(
    ma.command('kitchen_speaker', 'moveQueueItem', { item: 'qi-9', shift: 1.5 }),
  ).rejects.toThrow(/shift/);

  await expect(ma.command('kitchen_speaker', 'moveQueueItem', { shift: 1 })).rejects.toThrow(
    /queue item/,
  );
});

test('queue reads a window starting two before the track that plays, and marks the one playing', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const queue = ma.queue('kitchen_speaker', { limit: 3 });
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/get',
    args: { queue_id: 'kitchen_speaker' },
  });

  await answer(socket, {
    items: 1322,
    current_index: 49,
    current_item: { queue_item_id: 'qi-49' },
  });

  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/items',
    args: { queue_id: 'kitchen_speaker', limit: 3, offset: 47 },
  });

  await answer(socket, [
    { queue_item_id: 'qi-47', name: 'A - One', duration: 100 },
    { queue_item_id: 'qi-48', name: 'B - Two', duration: 110 },
    {
      queue_item_id: 'qi-49',
      name: 'C - Three',
      duration: 120,
      media_item: { name: 'Three', artists: [{ name: 'C' }], album: { name: 'Album' } },
    },
  ]);

  const result = await queue;
  expect(result).toMatchObject({ total: 1322, offset: 47 });
  expect(result.items.map((item) => item.id)).toEqual(['qi-47', 'qi-48', 'qi-49']);
  expect(result.items[2]).toMatchObject({
    title: 'Three',
    artist: 'C',
    album: 'Album',
    current: true,
  });

  expect(result.items[0]).not.toHaveProperty('current');
  await expect(ma.queue('nope', {})).rejects.toBeInstanceOf(UnknownEntityError);
});

test('a player with no queue has no items', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const queue = ma.queue('kitchen_speaker', {});
  await answer(socket, null);
  expect(await queue).toEqual({ items: [], total: 0, offset: 0 });
});

const alreadyAnswered = new WeakMap<FakeSocket, Set<string>>();

/** Answers every request of a kind that has not been answered yet, the way the server would. */
function answerAll(
  socket: () => FakeSocket,
  command: string,
  reply: (message: {
    message_id: string;
    args?: Record<string, unknown>;
  }) => Record<string, unknown>,
) {
  const answered = alreadyAnswered.get(socket()) ?? new Set<string>();
  alreadyAnswered.set(socket(), answered);
  for (const message of socket().sent.filter((sent) => sent.command === command)) {
    if (!answered.has(message.message_id)) {
      answered.add(message.message_id);
      socket().receive({ message_id: message.message_id, ...reply(message) });
    }
  }
}

const deletions = (socket: () => FakeSocket) =>
  socket()
    .sent.filter((sent) => sent.command === 'player_queues/delete_item')
    .map((sent) => sent.args?.item_id_or_index);

test('Clear while something plays takes out only what follows it, last first, and never the track playing', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const clearing = ma.command('kitchen_speaker', 'clearQueue');
  await flush();
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/get',
    args: { queue_id: 'kitchen_speaker' },
  });

  await answer(socket, { state: 'playing', current_index: 5, items: 9 });
  // What comes after the sixth track: from the seventh on.
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/items',
    args: { queue_id: 'kitchen_speaker', offset: 6 },
  });

  await answer(socket, [{ queue_item_id: 'q6' }, { queue_item_id: 'q7' }, { queue_item_id: 'q8' }]);
  expect(deletions(socket)).toEqual(['q8', 'q7', 'q6']);
  answerAll(socket, 'player_queues/delete_item', () => ({ result: null }));
  await flush();
  await clearing;
  // The track playing and what came before it were not touched, and Music Assistant's own clear
  // (which would take the playing track out too) was not used.
  expect(deletions(socket)).not.toContain('q5');
  expect(socket().sent.some((sent) => sent.command === 'player_queues/clear')).toBe(false);
});

test('Clear takes them out a batch at a time, not all at once', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const clearing = ma.command('kitchen_speaker', 'clearQueue');
  await flush();
  await answer(socket, { state: 'playing', current_index: 0, items: 121 });
  await answer(
    socket,
    Array.from({ length: 120 }, (_, index) => ({ queue_item_id: `q${index + 1}` })),
  );

  expect(deletions(socket)).toHaveLength(50);
  answerAll(socket, 'player_queues/delete_item', () => ({ result: null }));
  await flush();
  expect(deletions(socket)).toHaveLength(100);
  answerAll(socket, 'player_queues/delete_item', () => ({ result: null }));
  await flush();
  expect(deletions(socket)).toHaveLength(120);
  answerAll(socket, 'player_queues/delete_item', () => ({ result: null }));
  await flush();
  await clearing;
  // Last first.
  expect(deletions(socket)[0]).toBe('q120');
  expect(deletions(socket).at(-1)).toBe('q1');
});

test('a track that cannot be taken out (already loaded in the player) does not stop the rest', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const clearing = ma.command('kitchen_speaker', 'clearQueue');
  await flush();
  await answer(socket, { state: 'playing', current_index: 1, items: 5 });
  await answer(socket, [{ queue_item_id: 'a' }, { queue_item_id: 'b' }, { queue_item_id: 'c' }]);
  answerAll(socket, 'player_queues/delete_item', (message) =>
    message.args?.item_id_or_index === 'a'
      ? { error_code: 999, details: 'already loaded' }
      : { result: null },
  );

  await flush();
  // It carries on, and finishes without failing.
  await expect(clearing).resolves.toBeUndefined();
  expect(deletions(socket)).toEqual(['c', 'b', 'a']);
});

test('Clear with nothing playing empties the whole queue, since there is nothing to keep', async () => {
  const { ma, socket } = make();
  await connect(ma, socket);
  const clearing = ma.command('kitchen_speaker', 'clearQueue');
  await flush();
  await answer(socket, { state: 'idle', current_index: 49, items: 1322 });
  expect(socket().sent.at(-1)).toMatchObject({
    command: 'player_queues/clear',
    args: { queue_id: 'kitchen_speaker' },
  });

  expect(socket().sent.at(-1)!.args).not.toHaveProperty('skip_stop');
  await answer(socket, null);
  await clearing;
  expect(deletions(socket)).toEqual([]);
});

test('after a skip, the old track stays on show, with its time, whatever the queue and the player report until the new one starts', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ current_media: { title: 'Old', artist: 'A' } })]);
  const at = (seconds: number) => vi.setSystemTime(new Date(Date.UTC(2026, 0, 1) + seconds * 1000));
  const queue = (uri: string, elapsed: number) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        state: 'playing',
        elapsed_time: elapsed,
        current_item: { media_item: { uri } },
      },
    });

  const title = (name: string) =>
    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen_speaker',
      data: player({ current_media: { title: name, artist: 'A' } }),
    });

  at(0);
  queue('old', 6);
  at(10);
  queue('new', 0);
  title('New');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ media: { title: 'Old' }, position: 6 });

  // Three seconds on, both report the old track again, at its own position...
  at(13);
  queue('old', 13.4);
  title('Old');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ media: { title: 'Old' }, position: 6 });

  // ...before the new one really starts.
  at(14);
  queue('new', 0.5);
  title('New');
  expect(ma.getEntity('kitchen_speaker')).toMatchObject({ media: { title: 'New' }, position: 0.5 });
});

test('a skip as Music Assistant really reports it keeps the old track on show until the new one has started', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [player({ current_media: { title: 'One', artist: 'A' } })]);
  const at = (seconds: number) => vi.setSystemTime(new Date(Date.UTC(2026, 0, 1) + seconds * 1000));

  const queue = (state: string, elapsed: number, uri = 'two') =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        state,
        elapsed_time: elapsed,
        current_item: { media_item: { uri } },
      },
    });

  const playback = (state: string, title = 'Two') =>
    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen_speaker',
      data: player({ playback_state: state, current_media: { title, artist: 'A' } }),
    });

  const shown: (number | undefined)[] = [];
  const note = () =>
    shown.push((ma.getEntity('kitchen_speaker') as { position?: number } | undefined)?.position);

  at(0);
  queue('playing', 6, 'one');
  at(10);
  queue('playing', 0);
  playback('playing');
  note();
  at(11);
  queue('playing', 0);
  note();
  // Three seconds after the skip the player flips to idle and back before the track really starts.
  at(13);
  playback('idle');
  queue('idle', 0);
  note();
  at(13.1);
  playback('playing');
  queue('idle', 0);
  note();
  at(13.6);
  queue('playing', 0.5);
  note();

  // The old track stays on show, and keeps its time, until the new one has really started.
  expect(shown).toEqual([6, 6, 6, 6, 0.5]);
});

test('a Sonos that keeps playing the old track for 23 s after a skip: the old one stays on show until the new one has started', async () => {
  const { ma, socket } = make();
  await connect(ma, socket, [
    player({ current_media: { title: 'Old (Radio Edit)', artist: 'A' } }),
  ]);

  const at = (seconds: number) => vi.setSystemTime(new Date(Date.UTC(2026, 0, 1) + seconds * 1000));

  const queue = (state: string, elapsed: number, uri: string) =>
    socket().receive({
      event: 'queue_updated',
      object_id: 'kitchen_speaker',
      data: {
        queue_id: 'kitchen_speaker',
        state,
        elapsed_time: elapsed,
        current_item: { media_item: { uri } },
      },
    });

  const playback = (state: string, title: string) =>
    socket().receive({
      event: 'player_updated',
      object_id: 'kitchen_speaker',
      data: player({ playback_state: state, current_media: { title, artist: 'A' } }),
    });

  const seen: string[] = [];
  const note = () => {
    const entity = ma.getEntity('kitchen_speaker') as {
      playback: string;
      media?: { title?: string };
      position?: number;
    };

    seen.push(`${entity.media?.title}@${entity.position}`);
  };

  at(0);
  queue('playing', 0.7, 'old');
  at(5);
  // Next: the new track is announced, and the player says so.
  queue('playing', 0, 'new');
  playback('playing', 'New');
  note();
  // The Sonos goes on with the old one for 23 s, then reports it, with its own position.
  at(28);
  queue('playing', 19.2, 'old');
  playback('idle', 'Old (Radio Edit)');
  note();
  at(28.2);
  queue('playing', 0, 'old');
  playback('playing', 'Old (Radio Edit)');
  note();
  // And then the new one really starts.
  at(28.7);
  queue('playing', 0.48, 'new');
  playback('playing', 'New');
  note();

  expect(seen).toEqual([
    'Old (Radio Edit)@0.7',
    'Old (Radio Edit)@0.7',
    'Old (Radio Edit)@0.7',
    'New@0.48',
  ]);
});

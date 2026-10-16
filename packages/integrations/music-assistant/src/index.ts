import {
  assetUrl,
  BaseIntegration,
  parseEntityRef,
  UnknownEntityError,
  type AssetOptions,
  type BrowseItem,
  type BrowseKind,
  type BrowseQuery,
  type BrowseResult,
  type EntityInput,
  type EntityRef,
  type MediaPlayerSource,
  type QueueQuery,
  type QueueResult,
} from '@hashsome/core';
import { parseUri, PICTURE_SIZES, SHELVES, toBrowseItem, type MaItem } from './browse.ts';
import { groupOf } from './group.ts';
import { sourceOf, toMediaPlayer, type MaPlayer, type ToMediaPlayerOptions } from './mapper.ts';
import { toQueueItems, type MaQueueItem } from './queue.ts';
import { onPlayback, onQueue, positionOf, type Position } from './position.ts';
import { onReport, REVERT_WINDOW_MS, type Shown } from './revert.ts';

/**
 * Direct client for the Music Assistant WebSocket API (not via Home Assistant), reverse-engineered
 * from https://github.com/music-assistant/server (`music_assistant/controllers/webserver` and
 * `music_assistant/controllers/players`) — there is no published spec and no official JS client.
 *
 * Protocol: connect to `<url>/ws`; the server sends a server-info message first (ignored here).
 * The client authenticates with `{ message_id, command: "auth", args: { token } }`; the server
 * replies with a success/error result and, on success, starts pushing `player_added` /
 * `player_updated` / `player_removed` events unprompted. Commands are
 * `{ message_id, command: "players/cmd/<x>", args: {...} }`, answered by a result carrying the
 * same `message_id`.
 *
 * Local entity ids are the player's own `player_id` verbatim (`ma:<player_id>`), not a
 * `domain.name` pair like Home Assistant's — Music Assistant's player ids are opaque strings that
 * may themselves contain dots, so splitting on one would be ambiguous.
 *
 * Every player is a native `mediaPlayer` entity (see `@hashsome/core`'s model): there is no Home
 * Assistant vocabulary here, and `command()` maps the model's commands straight to `players/cmd/*`.
 */

export interface MusicAssistantOptions {
  /** Base URL, e.g. `http://mass.local:8095`. */
  url: string;

  /** Access token, created in Music Assistant under Settings → Profile. */
  token: string;

  /** Integration id used in entity refs. Defaults to `ma`. */
  id?: string;

  /** Whether what is played from a dashboard goes on with similar tracks once its queue runs out, as it does in
   * Spotify (Music Assistant's "don't stop the music" setting on the player's queue, turned on whenever something is
   * played). `false` leaves the setting as it is in Music Assistant. Default `true`. */
  autoplay?: boolean;

  /** Override for tests. */
  createSocket?: (url: string) => WebSocket;
  reconnectMinMs?: number;
  reconnectMaxMs?: number;
}

/** The most tracks Clear looks for after the one that plays, and how many it takes out at once. */
const UPCOMING_LIMIT = 5000;
const DELETE_BATCH = 50;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;

const isMaPlayer = (value: unknown): value is MaPlayer =>
  isRecord(value) && typeof value.player_id === 'string';

/** Maps a model command onto a Music Assistant `players/cmd/*` command, validating its arguments. */
function commandFor(
  name: string,
  args: Record<string, unknown> | undefined,
): { command: string; args?: Record<string, unknown>; idKey?: 'player_id' | 'queue_id' } {
  switch (name) {
    case 'play':
      return { command: 'players/cmd/play' };
    case 'pause':
      return { command: 'players/cmd/pause' };
    case 'togglePlay':
      return { command: 'players/cmd/play_pause' };
    case 'next':
      return { command: 'players/cmd/next' };
    case 'previous':
      return { command: 'players/cmd/previous' };
    case 'setVolume': {
      const volume = args?.volume;
      if (typeof volume !== 'number' || !Number.isFinite(volume)) {
        throw new Error('setVolume needs a numeric volume between 0 and 1');
      }

      return {
        command: 'players/cmd/volume_set',
        args: { volume_level: Math.round(Math.min(1, Math.max(0, volume)) * 100) },
      };
    }

    case 'setMuted':
      if (typeof args?.muted !== 'boolean') {
        throw new Error('setMuted needs a boolean "muted"');
      }

      return { command: 'players/cmd/volume_mute', args: { muted: args.muted } };
    case 'setShuffle':
      if (typeof args?.shuffle !== 'boolean') {
        throw new Error('setShuffle needs a boolean "shuffle"');
      }

      return {
        command: 'player_queues/shuffle',
        args: { shuffle_enabled: args.shuffle },
        idKey: 'queue_id',
      };
    case 'seek': {
      const position = args?.position;
      if (typeof position !== 'number' || !Number.isFinite(position)) {
        throw new Error('seek needs a numeric position in seconds');
      }

      // Queues are addressed by their own id, which is the player's.
      return {
        command: 'player_queues/seek',
        args: { position: Math.max(0, Math.round(position)) },
        idKey: 'queue_id',
      };
    }

    case 'playMedia': {
      const mode = args?.mode ?? 'play';
      if (typeof args?.item !== 'string' || args.item === '') {
        throw new Error('playMedia needs an item id');
      }

      if (!['play', 'replace', 'next', 'add'].includes(String(mode))) {
        throw new Error('"mode" must be play, replace, next or add');
      }

      const { context, shuffle } = args;
      if (context !== undefined && (typeof context !== 'string' || context === '')) {
        throw new Error('"context" must be the id of an album or playlist');
      }

      if (shuffle !== undefined && typeof shuffle !== 'boolean') {
        throw new Error('"shuffle" must be true or false');
      }

      return {
        command: 'player_queues/play_media',
        args: {
          // A track in an album or playlist: queue all of it and start at the track.
          media: context ?? args.item,
          option: mode,
          ...(context !== undefined ? { start_item: args.item } : {}),
          ...(shuffle !== undefined ? { shuffle } : {}),
        },
        idKey: 'queue_id',
      };
    }

    case 'playQueueItem':
    case 'removeQueueItem': {
      if (typeof args?.item !== 'string' || args.item === '') {
        throw new Error(`${name} needs the id of a queue item`);
      }

      return name === 'playQueueItem'
        ? { command: 'player_queues/play_index', args: { index: args.item }, idKey: 'queue_id' }
        : {
            command: 'player_queues/delete_item',
            args: { item_id_or_index: args.item },
            idKey: 'queue_id',
          };
    }

    case 'moveQueueItem': {
      if (typeof args?.item !== 'string' || args.item === '') {
        throw new Error('moveQueueItem needs the id of a queue item');
      }

      // Music Assistant reads a shift of 0 as "play it next", which is not a move: ask for a real one.
      if (!Number.isInteger(args.shift) || args.shift === 0) {
        throw new Error('"shift" must be a whole number of places, not 0');
      }

      return {
        command: 'player_queues/move_item',
        args: { queue_item_id: args.item, pos_shift: args.shift },
        idKey: 'queue_id',
      };
    }

    case 'clearQueue':
      // Not reached through `command()` while something plays: that takes out only what follows the
      // playing track, one by one (`#clearUpcoming`). This is for a player with nothing playing,
      // where the whole queue goes (and Music Assistant's own clear stops what is left).
      return { command: 'player_queues/clear', idKey: 'queue_id' };

    default:
      throw new Error(`A media player has no "${name}" command`);
  }
}

function wsUrl(url: string): string {
  const parsed = new URL(url);
  parsed.protocol = parsed.protocol === 'https:' ? 'wss:' : 'ws:';
  parsed.pathname = `${parsed.pathname.replace(/\/$/, '')}/ws`;
  return parsed.toString();
}

/** What a queue says that the player entity shows. */
interface QueueState {
  shuffle?: boolean;
  position: Position;

  /** What the queue was filled from. */
  source?: MediaPlayerSource;
}

/** The kinds of item a search finds, which are also Music Assistant's own names for them. */
const SEARCHABLE_KINDS: BrowseKind[] = ['album', 'artist', 'track', 'playlist', 'radio'];

/** How many of each kind an unfiltered search brings, and how many a search for one kind does. */
const SEARCH_LIMIT = 8;

/** The id of the first item of a read of a queue, or nothing. */
const firstItemId = (raw: unknown) =>
  Array.isArray(raw) && isRecord(raw[0]) && typeof raw[0].queue_item_id === 'string'
    ? raw[0].queue_item_id
    : '';

/** Queues longer than this are not read through to say how long they play, and how many items are read at a time. */
const QUEUE_DURATION_LIMIT = 2000;
const QUEUE_DURATION_PAGE = 200;
const SEARCH_LIMIT_ONE_KIND = 50;

const samePosition = (a: Position, b: Position) =>
  a.item === b.item &&
  a.elapsed === b.elapsed &&
  a.resume === b.resume &&
  a.held === b.held &&
  a.inherited === b.inherited;

/** Which track a queue is on, as something comparable: its address when it has one, else its name or place. */
function itemOf(queue: Record<string, unknown>): string | undefined {
  // The track's own address. The queue's id for the item is not kept when a stopped queue is
  // resumed, so it would make a resume look like a different track.
  const current = queue.current_item;
  if (isRecord(current)) {
    const media = current.media_item;
    if (isRecord(media) && typeof media.uri === 'string') {
      return media.uri;
    }

    if (typeof current.name === 'string') {
      return current.name;
    }

    if (typeof current.queue_item_id === 'string') {
      return current.queue_item_id;
    }
  }

  return typeof queue.current_index === 'number' ? `#${queue.current_index}` : undefined;
}

export class MusicAssistantIntegration extends BaseIntegration {
  readonly id: string;
  #options: MusicAssistantOptions;
  #socket: WebSocket | undefined;
  #nextMessageId = 1;

  /** What each player was last shown as, and when each was last asked to go to the previous track. */
  #shown = new Map<string, Shown<EntityInput & { kind: 'mediaPlayer' }>>();
  #wentBack = new Map<string, number>();
  #queueShown = new Map<string, Shown<{ media: { title: string }; playback: string }>>();

  /** The last raw player and what its queue says, so either can change without the other. A queue
   * has the player's id. */
  #players = new Map<string, MaPlayer>();
  #queues = new Map<string, QueueState>();

  /** How long each queue plays, with what it was worked out from. */
  #queueLengths = new Map<string, { key: string; seconds: number | undefined }>();

  #pending = new Map<
    string,
    { resolve: (result: unknown) => void; reject: (error: Error) => void }
  >();
  #stopped = true;
  #retry = 0;
  #timer: ReturnType<typeof setTimeout> | undefined;

  /** True once the initial handshake has succeeded at least once — only then does a later close
   * count as a "drop" worth auto-reconnecting; a close during the *first* attempt is a connect()
   * failure that the runtime's own startup retry loop owns (see `connect()`'s doc comment). */
  #everConnected = false;

  constructor(options: MusicAssistantOptions) {
    super();
    this.id = options.id ?? 'ma';
    this.#options = options;
  }

  /** Resolves once authenticated and the initial player list has been fetched. Later drops are
   * reconnected internally with backoff (mirrors how the runtime treats every integration: it
   * only calls `connect()` once, at startup). */
  connect(): Promise<void> {
    if (!this.#stopped) {
      return Promise.resolve();
    }

    this.#stopped = false;
    this.setStatus('connecting');
    return this.#open();
  }

  disconnect(): void {
    this.#stopped = true;
    clearTimeout(this.#timer);
    for (const { reject } of this.#pending.values()) {
      reject(new Error('Disconnected'));
    }

    this.#pending.clear();
    this.#socket?.close();
    this.#socket = undefined;
    this.setStatus('disconnected');
  }

  async command(entityId: string, name: string, args?: Record<string, unknown>): Promise<void> {
    if (!this.getEntity(entityId)) {
      throw new UnknownEntityError(this.id, entityId);
    }

    if (name === 'clearQueue') {
      await this.#clearUpcoming(entityId);
      return;
    }

    if (name === 'setGroupMembers' || name === 'leaveGroup' || name === 'takeOverGroup') {
      await this.#group(entityId, name, args);
      return;
    }

    if (name === 'previous') {
      this.#wentBack.set(entityId, Date.now());
    }

    // Playing something: let the queue go on with similar tracks when it runs out. Not waited for (nor does it matter if
    // it fails): Music Assistant takes the requests in order, so the setting is there when the item starts.
    if (
      name === 'playMedia' &&
      this.#options.autoplay !== false &&
      ['play', 'replace', undefined].includes(args?.mode as string | undefined)
    ) {
      this.#send('player_queues/autoplay', { queue_id: entityId, autoplay_enabled: true }).catch(
        () => {},
      );
    }

    const { command, args: commandArgs, idKey = 'player_id' } = commandFor(name, args);
    await this.#send(command, { [idKey]: entityId, ...commandArgs });
  }

  /**
   * Changes who plays together: `setGroupMembers` adds players to this one's stream and takes players out of it
   * (`players/cmd/set_members`, with this player as the target), and `leaveGroup` takes this one out of its group.
   * A leader leaving ends the group, which Music Assistant's own `ungroup` does not promise for a leader, so its
   * followers are taken out instead.
   */
  async #group(
    entityId: string,
    name: string,
    args: Record<string, unknown> | undefined,
  ): Promise<void> {
    if (name === 'takeOverGroup') {
      await this.#takeOver(entityId);
      return;
    }

    if (name === 'leaveGroup') {
      const player = this.#players.get(entityId);
      const followers = (player?.group_childs ?? []).filter((id) => id !== entityId);
      await (followers.length > 0
        ? this.#send('players/cmd/set_members', {
            target_player: entityId,
            player_ids_to_remove: followers,
          })
        : this.#send('players/cmd/ungroup', { player_id: entityId }));

      return;
    }

    const add = this.#playersArg(args?.add, 'add');
    const remove = this.#playersArg(args?.remove, 'remove');
    if (add.length === 0 && remove.length === 0) {
      return;
    }

    await this.#send('players/cmd/set_members', {
      target_player: entityId,
      ...(add.length > 0 ? { player_ids_to_add: add } : {}),
      ...(remove.length > 0 ? { player_ids_to_remove: remove } : {}),
    });
  }

  /**
   * A follower takes the stream over from its leader: it leaves the group, the stream's queue is moved to it (Music
   * Assistant carries on from the same track and place), the players that followed the leader are put behind it, and
   * the old leader is stopped.
   */
  async #takeOver(entityId: string): Promise<void> {
    const player = this.#players.get(entityId);
    const group = player
      ? groupOf(player, (id) => this.#players.get(id), this.#players.values())
      : undefined;

    if (!group || group.leader === entityId) {
      return;
    }

    const others = group.members.filter((id) => id !== entityId);
    await this.#send('players/cmd/ungroup', { player_id: entityId });
    await this.#send('player_queues/transfer', {
      source_queue_id: group.leader,
      target_queue_id: entityId,
      auto_play: true,
    });

    if (others.length > 0) {
      await this.#send('players/cmd/set_members', {
        target_player: entityId,
        player_ids_to_add: others,
      });
    }

    await this.#send('players/cmd/stop', { player_id: group.leader });
  }

  /** The ids of players given as refs, which must be players of this integration. */
  #playersArg(value: unknown, key: string): string[] {
    if (value === undefined) {
      return [];
    }

    if (!Array.isArray(value) || value.some((ref) => typeof ref !== 'string')) {
      throw new Error(`"${key}" needs a list of players`);
    }

    return (value as string[]).map((ref) => {
      const { integration, id } = parseEntityRef(ref);
      if (integration !== this.id || !this.getEntity(id)) {
        throw new Error(`"${ref}" is not a player of ${this.id}`);
      }

      return id;
    });
  }

  /**
   * Empties the queue after the track that plays, which stays where it is (in the queue and on the
   * player) and plays to its end. Music Assistant has no command for that: its `clear` takes the
   * playing track out of the queue too, and either stops it or, with `skip_stop`, leaves the player
   * playing what it had buffered for a few seconds. So the tracks after it are taken out by id,
   * last first, several at a time. A track Music Assistant has already loaded into the player's
   * buffer cannot be taken out and stays, so one more may follow. With nothing playing there is
   * nothing to keep, and the whole queue goes.
   */
  async #clearUpcoming(entityId: string): Promise<void> {
    const queue = await this.#send('player_queues/get', { queue_id: entityId });
    const playing = isRecord(queue) ? queue.current_index : undefined;
    const active = isRecord(queue) && (queue.state === 'playing' || queue.state === 'paused');
    if (!active || typeof playing !== 'number') {
      await this.#send('player_queues/clear', { queue_id: entityId });
      return;
    }

    const after = await this.#send('player_queues/items', {
      queue_id: entityId,
      offset: playing + 1,
      limit: UPCOMING_LIMIT,
    });

    const ids = (Array.isArray(after) ? (after as MaQueueItem[]) : [])
      .flatMap((item) => (typeof item.queue_item_id === 'string' ? [item.queue_item_id] : []))
      .toReversed();

    // By id, so they do not disturb one another, and one that cannot be taken out leaves the rest.
    for (let from = 0; from < ids.length; from += DELETE_BATCH) {
      await Promise.allSettled(
        ids
          .slice(from, from + DELETE_BATCH)
          .map((id) =>
            this.#send('player_queues/delete_item', { queue_id: entityId, item_id_or_index: id }),
          ),
      );
    }
  }

  /**
   * The player's queue: a window of `limit` tracks starting two before the one playing, with how many
   * the whole queue has (it can be thousands, left over from earlier playback), so a panel shows
   * what has just played and what is coming without pulling all of it.
   */
  async queue(entityId: string, query: QueueQuery): Promise<QueueResult> {
    if (!this.getEntity(entityId)) {
      throw new UnknownEntityError(this.id, entityId);
    }

    const queue = await this.#send('player_queues/get', { queue_id: entityId });
    if (!isRecord(queue)) {
      return { items: [], total: 0, offset: 0 };
    }

    const total = typeof queue.items === 'number' ? queue.items : 0;
    const playing = typeof queue.current_index === 'number' ? queue.current_index : 0;
    const offset = Math.max(0, playing - 2);
    const [raw, duration] = await Promise.all([
      this.#send('player_queues/items', {
        queue_id: entityId,
        limit: query.limit ?? 30,
        offset,
      }),
      query.duration ? this.#queueDuration(entityId, total).catch(() => undefined) : undefined,
    ]);

    const current = isRecord(queue.current_item) ? queue.current_item.queue_item_id : undefined;
    return {
      items: toQueueItems(
        Array.isArray(raw) ? (raw as MaQueueItem[]) : [],
        typeof current === 'string' ? current : undefined,
        this.#assetUrl,
      ),
      total,
      offset,
      ...(duration !== undefined ? { duration } : {}),
    };
  }

  /** How long the whole queue plays, in seconds: every item's, which the window the queue is read in does not hold. A
   * queue is long and Music Assistant gives its items only in full, so they are read once, in pages, and not again until
   * the queue is another: its size and its first and last item (two small reads) stand for it. Nothing for one too long
   * to be worth reading. */
  async #queueDuration(queueId: string, total: number): Promise<number | undefined> {
    if (total === 0) {
      return 0;
    }

    if (total > QUEUE_DURATION_LIMIT) {
      return undefined;
    }

    const [first, last] = await Promise.all([
      this.#send('player_queues/items', { queue_id: queueId, limit: 1, offset: 0 }),
      this.#send('player_queues/items', { queue_id: queueId, limit: 1, offset: total - 1 }),
    ]);

    const key = `${total}:${firstItemId(first)}:${firstItemId(last)}`;
    const known = this.#queueLengths.get(queueId);
    if (known?.key === key) {
      return known.seconds;
    }

    let seconds = 0;
    for (let at = 0; at < total; at += QUEUE_DURATION_PAGE) {
      const page = await this.#send('player_queues/items', {
        queue_id: queueId,
        limit: QUEUE_DURATION_PAGE,
        offset: at,
      });

      if (!Array.isArray(page)) {
        return undefined;
      }

      for (const item of page) {
        if (isRecord(item) && typeof item.duration === 'number') {
          seconds += item.duration;
        }
      }
    }

    this.#queueLengths.set(queueId, { key, seconds });
    return seconds;
  }

  /** One level of the library, or a search across it, through Music Assistant's own API. */
  async browse(entityId: string, query: BrowseQuery): Promise<BrowseResult> {
    if (!this.getEntity(entityId)) {
      throw new UnknownEntityError(this.id, entityId);
    }

    if (query.search !== undefined) {
      return this.#search(query.search, query.kind);
    }

    if (query.path === undefined) {
      return { items: SHELVES.map((shelf) => ({ ...shelf, playable: false, expandable: true })) };
    }

    const shelf = SHELVES.find((candidate) => candidate.id === query.path);
    const found = shelf ? await this.#shelfItems(shelf.id) : await this.#childItems(query.path);
    return { ...(shelf ? { title: shelf.title } : {}), items: found };
  }

  async #shelfItems(shelfId: string): Promise<BrowseItem[]> {
    const library: Record<string, [string, Record<string, unknown>]> = {
      'shelf:recent': ['music/recently_played_items', { limit: 25 }],
      'shelf:playlists': ['music/playlists/library_items', { limit: 200, order_by: 'name' }],
      'shelf:albums': ['music/albums/library_items', { limit: 200, order_by: 'name' }],
      'shelf:artists': ['music/artists/library_items', { limit: 200, order_by: 'name' }],
      'shelf:radio': ['music/radios/library_items', { limit: 200, order_by: 'name' }],
    };

    const [command, args] = library[shelfId] ?? [];
    return command ? this.#items(await this.#send(command, args ?? {})) : [];
  }

  /** What is inside an album, a playlist or an artist. */
  async #childItems(uri: string): Promise<BrowseItem[]> {
    const { provider, type, id } = parseUri(uri);
    const command = {
      album: 'music/albums/album_tracks',
      playlist: 'music/playlists/playlist_tracks',
      artist: 'music/artists/artist_albums',
    }[type];

    if (!command) {
      throw new Error(`"${uri}" has nothing inside it`);
    }

    return this.#items(
      await this.#send(command, { item_id: id, provider_instance_id_or_domain: provider }),
    );
  }

  async #search(text: string, kind?: BrowseKind): Promise<BrowseResult> {
    // One kind asks for more of it: that is what narrowing to it is for. A kind that is not a media type is nothing.
    if (kind !== undefined && !SEARCHABLE_KINDS.includes(kind)) {
      return { title: `Results for “${text}”`, items: [] };
    }

    const results = await this.#send('music/search', {
      search_query: text,
      media_types: kind ? [kind] : SEARCHABLE_KINDS,
      limit: kind ? SEARCH_LIMIT_ONE_KIND : SEARCH_LIMIT,
    });

    const groups = isRecord(results)
      ? [results.albums, results.artists, results.tracks, results.playlists, results.radio]
      : [];

    return { title: `Results for “${text}”`, items: groups.flatMap((group) => this.#items(group)) };
  }

  #items(list: unknown): BrowseItem[] {
    return Array.isArray(list)
      ? list.flatMap((item) => {
          const mapped = isRecord(item) ? toBrowseItem(item as MaItem, this.#assetUrl) : undefined;
          return mapped ? [mapped] : [];
        })
      : [];
  }

  async #open(): Promise<void> {
    const socket = (this.#options.createSocket ?? ((url) => new WebSocket(url)))(
      wsUrl(this.#options.url),
    );

    this.#socket = socket;

    socket.addEventListener('message', (event) => {
      this.#handle(String((event as MessageEvent).data));
    });

    const closed = new Promise<never>((_resolve, reject) => {
      socket.addEventListener('close', () => {
        if (this.#socket !== socket) {
          return;
        }

        this.#socket = undefined;
        for (const { reject: rejectPending } of this.#pending.values()) {
          rejectPending(new Error('Connection lost'));
        }

        this.#pending.clear();
        reject(new Error('Music Assistant connection closed'));
        // A close during the first handshake is this attempt failing (status is already
        // 'error', set below) — only a drop after a prior success should move to 'disconnected'
        // and trigger our own reconnect; a first-attempt failure is retried by the caller.
        if (this.#everConnected) {
          this.setStatus('disconnected');
          if (!this.#stopped) {
            this.#scheduleReconnect();
          }
        }
      });
    });

    const opened = new Promise<void>((resolve, reject) => {
      socket.addEventListener('open', () => resolve());
      socket.addEventListener('error', () =>
        reject(new Error('Music Assistant connection failed')),
      );
    });

    try {
      await Promise.race([opened, closed]);
      await Promise.race([this.#authenticateAndLoad(), closed]);
      this.#everConnected = true;
      this.#retry = 0;
      this.setStatus('connected');
    } catch (error) {
      this.setStatus('error');
      socket.close();
      throw error;
    }
  }

  #scheduleReconnect(): void {
    const min = this.#options.reconnectMinMs ?? 500;
    const max = this.#options.reconnectMaxMs ?? 10_000;
    const delay = Math.min(max, min * 2 ** this.#retry++);
    this.#timer = setTimeout(() => {
      if (this.#stopped) {
        return;
      }

      this.setStatus('connecting');
      this.#open().catch(() => this.#scheduleReconnect());
    }, delay);
  }

  async #authenticateAndLoad(): Promise<void> {
    await this.#send('auth', { token: this.#options.token });
    const players = await this.#send('players/all', {});
    const next = new Map<string, EntityInput>();
    this.#players.clear();
    if (Array.isArray(players)) {
      for (const player of players) {
        if (isMaPlayer(player)) {
          this.#players.set(player.player_id, player);
          next.set(player.player_id, this.#entityFor(player));
        }
      }
    }

    this.replaceEntities(next);
    // Shuffle lives on the queues. They are asked for after the players are known, and without
    // holding the connection up: a server that does not answer just leaves shuffle unreported.
    this.#send('player_queues/all', {}).then(
      (queues) => {
        if (Array.isArray(queues)) {
          queues.forEach((queue) => this.#applyQueue(queue));
        }
      },
      () => {},
    );
  }

  /** Shows a player as it is now, unless it is only flickering back to the track it just left (see `revert.ts`). */
  #show(player: MaPlayer): void {
    const input = this.#entityFor(player);
    if (input.kind !== 'mediaPlayer') {
      this.setEntity(player.player_id, input);
      return;
    }

    // A skip the player has not followed yet: the old track stays on show, as it was last shown.
    const last = this.#shown.get(player.player_id)?.last;
    if (last && this.#queueShown.get(player.player_id)?.incoming !== undefined) {
      const { media, position, duration, positionUpdatedAt, playback } = last;
      this.setEntity(player.player_id, {
        ...input,
        media,
        position,
        duration,
        positionUpdatedAt,
        playback,
      } as EntityInput);

      return;
    }

    const asked = this.#wentBack.get(player.player_id);
    const { state, show } = onReport(
      this.#shown.get(player.player_id),
      input,
      Date.now(),
      asked !== undefined && Date.now() - asked < REVERT_WINDOW_MS,
      input.playback === 'playing' && (input.position ?? 0) >= 0.05,
      false,
    );

    this.#shown.set(player.player_id, state);
    this.setEntity(player.player_id, show);
  }

  /** What a player looks like with its queue's shuffle setting and position copied onto it. The
   * position is the queue's, not the player's: when playback is resumed Music Assistant starts a
   * new stream at the saved spot, and the player's own counter then starts from 0 again, while
   * the queue keeps counting through the track. */
  #entityFor(player: MaPlayer): EntityInput {
    const group = groupOf(player, (id) => this.#players.get(id), this.#players.values());
    const options = { ref: this.#refOf, group };
    const leader =
      group && group.leader !== player.player_id ? this.#players.get(group.leader) : undefined;

    // A follower is grouped with nobody else itself (Music Assistant lists none for it), but more players can still be
    // added to the stream it follows, by its leader: those are the ones it can be grouped with, and the leader too.
    const own = this.#ownEntity(
      leader && !(player.can_group_with ?? []).length
        ? {
            ...player,
            can_group_with: [leader.player_id, ...(leader.can_group_with ?? [])].filter(
              (id) => id !== player.player_id,
            ),
          }
        : player,
      options,
    );

    if (!leader || !('playback' in own) || own.availability !== 'ready') {
      return own;
    }

    // A player that follows plays what its leader plays: it shows the leader's track, where it is in it and
    // whether it plays, and keeps what is its own (name, volume, what it can do).
    const lead = this.#ownEntity(leader, {
      ref: this.#refOf,
      group: groupOf(leader, (id) => this.#players.get(id)),
    });

    if (lead.kind !== 'mediaPlayer' || lead.availability !== 'ready') {
      return own;
    }

    const { media, position, positionUpdatedAt, duration, shuffle, source } = lead;
    return {
      ...own,
      ...(source ? { source } : {}),
      playback: lead.playback,
      ...(media ? { media } : {}),
      ...(position !== undefined ? { position } : {}),
      ...(positionUpdatedAt ? { positionUpdatedAt } : {}),
      ...(duration !== undefined ? { duration } : {}),
      ...(shuffle !== undefined ? { shuffle } : {}),
    } as EntityInput;
  }

  /** The ref of a player of this integration. */
  #refOf = (playerId: string): EntityRef => `${this.id}:${playerId}`;

  /** Where a browser asks the runtime for a picture of Music Assistant's. */
  #assetUrl = (path: string): string => assetUrl(this.id, path);

  /** Music Assistant's picture addresses are made from the picture's own identity: the same one is the same picture. */
  isStableAsset(path: string): boolean {
    return path.startsWith('/imageproxy/');
  }

  /** A picture Music Assistant serves (what is playing, an item's), fetched with the token, at the size asked for (see
   * `PICTURE_SIZES`); the size in the path when none is said. Only one of its `/imageproxy/` paths is accepted,
   * never another path or another address. */
  async fetchAsset(path: string, options: AssetOptions = {}): Promise<Response> {
    const base = new URL(this.#options.url);
    const target = new URL(path, base);
    if (!path.startsWith('/imageproxy/') || target.origin !== base.origin) {
      throw new Error('Not a picture of Music Assistant');
    }

    if (options.size !== undefined) {
      target.searchParams.set('size', String(PICTURE_SIZES[options.size]));
    }

    return fetch(target, { headers: { Authorization: `Bearer ${this.#options.token}` } });
  }

  /** A player as it is by itself, with its queue's shuffle setting and position copied onto it. */
  #ownEntity(player: MaPlayer, options: ToMediaPlayerOptions): EntityInput {
    const queue = this.#queues.get(player.player_id);
    options = {
      ...options,
      assetUrl: this.#assetUrl,
      ...(queue?.source ? { source: queue.source } : {}),
    };

    if (!queue) {
      // A stopped player's own counter is back at the start: until the queue says more, no position.
      return toMediaPlayer(
        player.playback_state === 'idle'
          ? { ...player, elapsed_time: null, elapsed_time_last_updated: null }
          : player,
        options,
      );
    }

    const position = positionOf(queue.position, player.playback_state);
    return toMediaPlayer(
      {
        ...player,
        ...(queue.shuffle !== undefined ? { shuffle_enabled: queue.shuffle } : {}),
        ...(position !== undefined
          ? { elapsed_time: position, elapsed_time_last_updated: queue.position.elapsedAt }
          : {}),
      },
      options,
    );
  }

  /** Shows again the players whose group depends on this one, which a change to it can change: its followers, now and
   * before. A follower shows its leader's track, and who is in the group comes from the leader. */
  #showGroup(before: MaPlayer | undefined, now: MaPlayer): void {
    const ids = new Set([...(before?.group_childs ?? []), ...(now.group_childs ?? [])]);
    for (const [id, other] of this.#players) {
      if (other.synced_to === now.player_id || other.active_group === now.player_id) {
        ids.add(id);
      }
    }

    ids.delete(now.player_id);
    for (const id of ids) {
      const other = this.#players.get(id);
      if (other) {
        this.#show(other);
      }
    }
  }

  #applyQueue(queue: unknown): void {
    if (!isRecord(queue) || typeof queue.queue_id !== 'string') {
      return;
    }

    const known = this.#queues.get(queue.queue_id) ?? { position: {} };
    const player = this.#players.get(queue.queue_id);
    const item = itemOf(queue);
    const elapsedNow = typeof queue.elapsed_time === 'number' ? queue.elapsed_time : 0;

    // The queue is where a skip shows first: the new track is announced at its start while the player
    // goes on with the old one, and flickers before the new one starts. Until it does, its reports are
    // not believed (see `revert.ts`), and what is shown stays as it was.
    if (item !== undefined) {
      const asked = this.#wentBack.get(queue.queue_id);
      const seen = onReport(
        this.#queueShown.get(queue.queue_id),
        { media: { title: item }, playback: String(queue.state) },
        Date.now(),
        asked !== undefined && Date.now() - asked < REVERT_WINDOW_MS,
        queue.state === 'playing' && elapsedNow >= 0.05,
      );

      this.#queueShown.set(queue.queue_id, seen.state);
      if (seen.held) {
        return;
      }
    }

    const message = {
      item,
      elapsed: typeof queue.elapsed_time === 'number' ? queue.elapsed_time : undefined,
      resume: typeof queue.resume_pos === 'number' ? queue.resume_pos : undefined,
    };

    // The player's state says whether it is stopped; the queue's own `state` is not kept in step.
    const playback =
      player?.playback_state ?? (typeof queue.state === 'string' ? queue.state : undefined);

    const source = sourceOf(queue);
    const { source: _before, ...rest } = known;
    const next: QueueState = {
      ...rest,
      ...(typeof queue.shuffle_enabled === 'boolean' ? { shuffle: queue.shuffle_enabled } : {}),
      position: onQueue(known.position, message, playback, Date.now() / 1000),
      ...(source ? { source } : {}),
    };

    this.#changeQueue(queue.queue_id, known, next);
  }

  /** Keeps a queue's new state and, when something it shows changed, the player's entity. */
  #changeQueue(id: string, known: QueueState, next: QueueState): void {
    if (
      next.shuffle === known.shuffle &&
      samePosition(next.position, known.position) &&
      next.source?.title === known.source?.title &&
      next.source?.kind === known.source?.kind
    ) {
      this.#queues.set(id, next);
      return;
    }

    this.#queues.set(id, next);
    const player = this.#players.get(id);
    if (player) {
      this.#show(player);
      this.#showGroup(player, player);
    }
  }

  /** A player changed state: a stop remembers where it got to, a start from a stop begins there. */
  #playerChanged(id: string, from: string | undefined, to: string | undefined): void {
    const known = this.#queues.get(id);
    if (known) {
      this.#queues.set(id, {
        ...known,
        position: onPlayback(known.position, from, to, Date.now() / 1000),
      });
    }
  }

  #send(command: string, args: Record<string, unknown>): Promise<unknown> {
    const socket = this.#socket;
    if (!socket) {
      return Promise.reject(new Error('Not connected'));
    }

    const messageId = String(this.#nextMessageId++);
    return new Promise((resolve, reject) => {
      this.#pending.set(messageId, { resolve, reject });
      socket.send(JSON.stringify({ message_id: messageId, command, args }));
    });
  }

  #handle(raw: string): void {
    let message: unknown;
    try {
      message = JSON.parse(raw);
    } catch {
      return;
    }

    if (!isRecord(message)) {
      return;
    }

    if (typeof message.event === 'string') {
      this.#handleEvent(message.event, message.object_id, message.data);
      return;
    }

    if (typeof message.message_id !== 'string') {
      return;
    }

    const pending = this.#pending.get(message.message_id);
    if (!pending) {
      return;
    }

    this.#pending.delete(message.message_id);
    if (typeof message.error_code === 'number') {
      pending.reject(
        new Error(
          typeof message.details === 'string'
            ? message.details
            : `Music Assistant error ${message.error_code}`,
        ),
      );
    } else if ('result' in message) {
      pending.resolve(message.result);
    }
  }

  #handleEvent(event: string, objectId: unknown, data: unknown): void {
    switch (event) {
      case 'player_added':
      case 'player_updated':
        if (isMaPlayer(data)) {
          this.#playerChanged(
            data.player_id,
            this.#players.get(data.player_id)?.playback_state,
            data.playback_state,
          );

          const before = this.#players.get(data.player_id);
          this.#players.set(data.player_id, data);
          this.#show(data);
          this.#showGroup(before, data);
        }

        return;
      case 'queue_added':
      case 'queue_updated':
        this.#applyQueue(data);
        return;
      case 'player_removed':
        if (typeof objectId === 'string') {
          this.#players.delete(objectId);
          this.#queues.delete(objectId);
          this.setEntity(objectId, undefined);
        }

        return;
      default:
        return;
    }
  }
}

import type { EntityInput, EntityRef, MediaPlayerSource } from '@hashsome/core';
import { PICTURE_SIZE } from './browse.ts';
import type { MaGroup } from './group.ts';

export interface MaPlayerMedia {
  title?: string | null;
  artist?: string | null;
  album?: string | null;
  image_url?: string | null;
  duration?: number | null;
}

export interface MaPlayer {
  player_id: string;
  display_name?: string | null;
  name?: string | null;
  available?: boolean;
  playback_state?: string;
  volume_level?: number | null;
  volume_muted?: boolean | null;
  current_media?: MaPlayerMedia | null;

  /** The queue's shuffle setting, which Music Assistant keeps on the queue, not on the player: the
   * integration copies it onto the player it belongs to. */
  shuffle_enabled?: boolean | null;

  /** Seconds into the current item, as of `elapsed_time_last_updated` (a UTC epoch in seconds). */
  elapsed_time?: number | null;
  elapsed_time_last_updated?: number | null;

  /** The players this one leads, which can include itself. */
  group_childs?: string[] | null;

  /** The player this one follows. */
  synced_to?: string | null;

  /** The group player this one is a child of. */
  active_group?: string | null;

  /** The players it can be grouped with, by id. */
  can_group_with?: string[] | null;

  /** What it can do, by Music Assistant's names (`set_members` is being grouped). */
  supported_features?: string[] | null;
}

export interface ToMediaPlayerOptions {
  /** The ref of a player of this integration, by its id. Default `ma:<id>`. */
  ref?: (playerId: string) => EntityRef;

  /** The group the player plays in, from `groupOf`. */
  group?: MaGroup | undefined;

  /** What its queue was filled from, from `sourceOf`. */
  source?: MediaPlayerSource | undefined;

  /** Addresses a picture Music Assistant serves itself through the runtime (the runtime fetches it with the token), as `assetUrl` of `@hashsome/core` does. Without it such a picture keeps the address Music Assistant gave. */
  assetUrl?: ((path: string) => string) | undefined;
}

/** The path of a picture Music Assistant serves itself (`/imageproxy/…`), from the address it gave for it, at the size
 * the displays use. That address
 * is Music Assistant's own for itself, which a browser away from the server cannot open and an https page will not
 * load, so it is asked for through the runtime instead. A picture from anywhere else (a streaming service's own
 * address) has none and is left as it is. */
export function ownPicturePath(url: string): string | undefined {
  try {
    const parsed = new URL(url);
    if (!parsed.pathname.startsWith('/imageproxy/')) {
      return undefined;
    }

    // It gave one at 512 pixels; the displays do not need so much.
    parsed.searchParams.set('size', String(PICTURE_SIZE));
    return `${parsed.pathname}${parsed.search}`;
  } catch {
    return undefined;
  }
}

const PLAYBACK: Record<string, 'playing' | 'paused' | 'idle' | 'buffering'> = {
  playing: 'playing',
  paused: 'paused',
  idle: 'idle',
  buffering: 'buffering',
};

/** The address a browser is given for a picture: through the runtime for one Music Assistant serves, else as it was. */
function artworkOf(url: string, assetUrl: ToMediaPlayerOptions['assetUrl']): string {
  const path = assetUrl ? ownPicturePath(url) : undefined;
  return path !== undefined && assetUrl ? assetUrl(path) : url;
}

/** Maps a Music Assistant player to the generic `mediaPlayer` entity (everything but `ref`). */
export function toMediaPlayer(player: MaPlayer, options: ToMediaPlayerOptions = {}): EntityInput {
  const media = player.current_media ?? undefined;
  const available = player.available !== false;
  const ref = options.ref ?? ((id: string): EntityRef => `ma:${id}`);
  const groupable = (player.supported_features ?? []).includes('set_members');
  const others = (player.can_group_with ?? []).filter((id) => id !== player.player_id);
  return {
    kind: 'mediaPlayer',
    name: player.display_name ?? player.name ?? player.player_id,
    availability: available ? 'ready' : 'unavailable',
    playback: available ? (PLAYBACK[player.playback_state ?? ''] ?? 'idle') : 'off',
    muted: player.volume_muted === true,
    ...(typeof player.volume_level === 'number' ? { volume: player.volume_level / 100 } : {}),
    ...(media
      ? {
          media: {
            ...(media.title ? { title: media.title } : {}),
            ...(media.artist ? { artist: media.artist } : {}),
            ...(media.album ? { album: media.album } : {}),
            ...(media.image_url
              ? { artworkUrl: artworkOf(media.image_url, options.assetUrl) }
              : {}),
          },
        }
      : {}),
    ...(typeof player.shuffle_enabled === 'boolean' ? { shuffle: player.shuffle_enabled } : {}),
    ...(typeof player.elapsed_time === 'number' &&
    typeof player.elapsed_time_last_updated === 'number'
      ? {
          position: player.elapsed_time,
          positionUpdatedAt: new Date(player.elapsed_time_last_updated * 1000).toISOString(),
        }
      : {}),
    ...(typeof media?.duration === 'number' ? { duration: media.duration } : {}),
    ...(options.group
      ? { group: { leader: ref(options.group.leader), members: options.group.members.map(ref) } }
      : {}),
    ...(groupable && others.length > 0 ? { groupable: others.map(ref) } : {}),
    ...(options.source ? { source: options.source } : {}),
    capabilities: {
      volume: typeof player.volume_level === 'number',
      mute: typeof player.volume_muted === 'boolean',
      next: true,
      previous: true,
      browse: true,
      search: true,
      seek: true,
      shuffle: true,
      queue: true,
      transfer: false,
      group: groupable,
    },
  };
}

const SOURCE_KINDS = ['playlist', 'album', 'artist', 'radio'] as const;

/** What a Music Assistant queue was filled from: the first of its `sources` that is a playlist, album, artist or radio
 * station. A queue of tracks put in one by one, or of a single track, has none. */
export function sourceOf(queue: Record<string, unknown>): MediaPlayerSource | undefined {
  if (!Array.isArray(queue.sources)) {
    return undefined;
  }

  for (const entry of queue.sources) {
    if (typeof entry !== 'object' || entry === null) {
      continue;
    }

    const {
      name,
      uri,
      media_type: type,
    } = entry as { name?: unknown; uri?: unknown; media_type?: unknown };

    const kind = SOURCE_KINDS.find((candidate) => candidate === type);
    if (kind && typeof name === 'string' && name !== '' && typeof uri === 'string' && uri !== '') {
      return { id: uri, title: name, kind };
    }
  }

  return undefined;
}

import type { EntityRef } from '../entity.ts';
import type { EntityBase } from './base.ts';

export type PlaybackState = 'playing' | 'paused' | 'idle' | 'off' | 'buffering';

/** Players that play one stream together. */
export interface MediaPlayerGroup {
  /** The player the stream belongs to, which the others follow. It is this player's own ref on the leader. */
  leader: EntityRef;

  /** The players that follow the leader, not counting it. */
  members: EntityRef[];
}

/** The playlist, album, artist or radio station a player is playing from. */
export interface MediaPlayerSource {
  title: string;
  kind?: 'playlist' | 'album' | 'artist' | 'radio';
}

export interface MediaPlayerEntity extends EntityBase<'mediaPlayer'> {
  playback: PlaybackState;
  media?: { title?: string; artist?: string; album?: string; artworkUrl?: string };

  /** What the queue was filled from, to say what is playing "from": the playlist, album, artist or radio station.
   * Absent for a track played on its own, or when the backend does not say. */
  source?: MediaPlayerSource;

  /** Where playback is in the current item, in seconds, as of `positionUpdatedAt`. A player that
   * is playing advances from there, so a UI interpolates instead of waiting for updates. */
  position?: number;

  /** Length of the current item, in seconds. Absent for a stream with no end. */
  duration?: number;

  /** ISO 8601: when `position` was true. */
  positionUpdatedAt?: string;

  /** Whether the player plays its queue in a random order. Absent when the player has no shuffle. */
  shuffle?: boolean;

  /** 0..1. Absent when the player has no volume control. */
  volume?: number;
  muted: boolean;

  /** The group this player plays in with others, the same on every one of them (`leader` is the player the
   * stream belongs to, so `group.leader === ref` says this one leads). Absent while it plays alone. A player
   * that follows shows what its leader plays. */
  group?: MediaPlayerGroup;

  /** The players this one can be grouped with: what to offer to add to its stream. Absent when it cannot be grouped
   * or the backend does not say. */
  groupable?: EntityRef[];
  capabilities: {
    volume: boolean;
    mute: boolean;
    next: boolean;
    previous: boolean;

    /** Has a browsable library (`Integration.browse`). */
    browse: boolean;

    /** The library can be searched (`BrowseQuery.search`). */
    search: boolean;

    /** Playback can be moved to a position (`seek`). */
    seek: boolean;

    /** Shuffle can be turned on and off (`setShuffle`). */
    shuffle: boolean;

    /** Has a queue that can be read (`Integration.queue`) and changed (`playQueueItem`, `removeQueueItem`, `moveQueueItem`, `clearQueue`). */
    queue: boolean;

    /** Playback can be moved to another player. */
    transfer: boolean;

    /** Can be grouped with other players (`setGroupMembers`, `leaveGroup`); `groupable` says with which. */
    group: boolean;
  };
}

export interface MediaPlayerCommands {
  play: void;
  pause: void;
  togglePlay: void;
  next: void;
  previous: void;

  /** `volume` is 0..1. */
  setVolume: { volume: number };
  setMuted: { muted: boolean };

  /** `position` is in seconds. */
  seek: { position: number };
  setShuffle: { shuffle: boolean };

  /** Plays a `BrowseItem` from this player's own library: `item` is its `id`. `mode` defaults to
   * `play`: start it now and leave the queue after it. `replace` clears the queue first, `next` and
   * `add` queue it. `context` is the album or playlist the `item` (a track) is in: a player that can
   * queues the whole of it and starts at the track, instead of the track alone. `shuffle` plays what
   * is queued in a random order, or in order when `false`; a player that cannot ignores it. */
  playMedia: {
    item: string;
    mode?: 'play' | 'replace' | 'next' | 'add';
    context?: string;
    shuffle?: boolean;
  };

  /** Jumps to a track in the queue and plays it: `item` is a `QueueItem`'s `id`. */
  playQueueItem: { item: string };

  /** Takes a track out of the queue: `item` is a `QueueItem`'s `id`. */
  removeQueueItem: { item: string };

  /** Moves a track in the queue: `item` is a `QueueItem`'s `id`, `shift` is by how many places, later
   * when positive and earlier when negative (never 0). */
  moveQueueItem: { item: string; shift: number };

  /** Empties the queue after the track playing, which stays in the queue and on the player and plays
   * to its end. A track the player has already loaded cannot be taken out and may follow. With nothing
   * playing, the whole queue goes. */
  clearQueue: void;

  /** Adds players to this player's stream and takes players out of it, this player being its leader: it starts a
   * group when it has none. A player that is playing or in another group leaves that to join. Not for a player that
   * follows another: ask its leader. Players are `groupable` ones of the same backend; an empty change does nothing. */
  setGroupMembers: { add?: EntityRef[]; remove?: EntityRef[] };

  /** Takes this player out of the group it plays in, which goes on without it. When it leads, the group is
   * over: everyone else stops following it. Nothing, for a player that plays alone. */
  leaveGroup: void;

  /** For a player that follows another: takes the stream over. The player that led it leaves the group (and stops),
   * and the others go on playing, now with this player as their leader, from where the stream was. Nothing, for a
   * player that does not follow one. */
  takeOverGroup: void;
}

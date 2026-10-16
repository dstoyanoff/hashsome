/** One track in a player's queue. */
export interface QueueItem {
  /** Stable for the item, whatever its place: pass it to the `playQueueItem`, `removeQueueItem` and `moveQueueItem` commands. */
  id: string;
  title: string;
  artist?: string;
  album?: string;
  artworkUrl?: string;

  /** In seconds. */
  duration?: number;

  /** The track playing now. */
  current?: boolean;
}

export interface QueueQuery {
  /** How many items to return, starting a little before the one playing. Default 30. */
  limit?: number;

  /** Also work out how long the whole queue plays (`QueueResult.duration`). It can mean reading every item of a long queue, once, so it is only done when asked for. */
  duration?: boolean;
}

export interface QueueResult {
  /** In the order they play, starting a little before the current one; empty when the player has no queue. */
  items: QueueItem[];

  /** How many items the whole queue has, which can be far more than `items`. */
  total: number;

  /** How many items come before `items[0]`. */
  offset: number;

  /** How long the whole queue plays, in seconds, when the backend can say: the sum of every item's, not only those in `items`. Absent when it cannot. */
  duration?: number;
}

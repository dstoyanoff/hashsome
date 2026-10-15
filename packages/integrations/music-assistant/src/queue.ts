import type { QueueItem } from '@hashsome/core';
import { artworkOf, type MaItem } from './browse.ts';

/** One entry of `player_queues/items`: the track as it sits in the queue. Plain JSON, with anything
 * missing simply left out of the result. */
export interface MaQueueItem {
  queue_item_id?: string;

  /** `Artist - Title`, as the queue labels it. */
  name?: string;
  duration?: number | null;
  image?: MaItem['image'];
  media_item?: (MaItem & { album?: { name?: string } | null }) | null;
}

/** The queue as a list of tracks. The title is the track's own name (the queue's `name` is
 * `Artist - Title`), the artists are all of them, and `current` marks the one that has the queue's
 * `current_item`. An entry without an id cannot be jumped to or removed, so it is left out. */
export function toQueueItems(
  items: readonly MaQueueItem[],
  currentItemId: string | undefined,
  assetUrl?: (path: string) => string,
): QueueItem[] {
  return items.flatMap((item) => {
    if (typeof item.queue_item_id !== 'string' || item.queue_item_id === '') {
      return [];
    }

    const track = item.media_item ?? undefined;
    const artists = (track?.artists ?? []).flatMap((artist) =>
      typeof artist.name === 'string' && artist.name !== '' ? [artist.name] : [],
    );

    const title = track?.name ?? item.name;
    const album = track?.album?.name;
    const artwork = artworkOf({ ...(item.image ? { image: item.image } : {}), ...track }, assetUrl);
    return [
      {
        id: item.queue_item_id,
        title: title ?? '',
        ...(artists.length > 0 ? { artist: artists.join(', ') } : {}),
        ...(album ? { album } : {}),
        ...(artwork ? { artworkUrl: artwork } : {}),
        ...(typeof item.duration === 'number' ? { duration: item.duration } : {}),
        ...(item.queue_item_id === currentItemId ? { current: true } : {}),
      },
    ];
  });
}

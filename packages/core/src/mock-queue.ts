import type { BrowseItem, Entity, QueueItem, QueueQuery, QueueResult } from './model/index.ts';

/** How many tracks the made-up queue has. */
const TOTAL = 24;

/** How many tracks are before the current one: a little of what has just played. */
const BEFORE = 2;

/**
 * A made-up queue for a mock player that has one: the tracks of the mock library, round and round,
 * with the one the player plays third. It is the same every time, so a test or a screenshot can count on it.
 * Anything that is not a media player with a queue has none.
 */
export function mockQueue(
  entity: Entity | undefined,
  library: Record<string, { items: BrowseItem[] }>,
  query: QueueQuery,
): QueueResult {
  if (entity?.kind !== 'mediaPlayer' || !entity.capabilities.queue) {
    return { items: [], total: 0, offset: 0 };
  }

  const tracks = [
    ...new Map(
      Object.values(library)
        .flatMap((folder) => folder.items)
        .filter((item) => item.kind === 'track')
        .map((item) => [item.id, item]),
    ).values(),
  ];

  if (tracks.length === 0) {
    return { items: [], total: 0, offset: 0 };
  }

  // The track the player says is playing is the one that plays here, so the two agree.
  const playing = Math.max(
    0,
    tracks.findIndex((track) => track.title === entity.media?.title),
  );

  const items: QueueItem[] = Array.from({ length: TOTAL }, (_, index) => {
    const track = tracks[(playing + index + tracks.length * TOTAL - BEFORE) % tracks.length]!;
    return {
      id: `q${index}-${track.id}`,
      title: track.title,
      ...(track.subtitle ? { artist: track.subtitle } : {}),
      ...(track.artworkUrl ? { artworkUrl: track.artworkUrl } : {}),
      duration: 170 + ((index * 37) % 90),
      ...(index === BEFORE ? { current: true } : {}),
    };
  });

  return {
    items: items.slice(0, query.limit ?? 30),
    total: TOTAL,
    offset: 0,
    duration: items.reduce((sum, item) => sum + (item.duration ?? 0), 0),
  };
}

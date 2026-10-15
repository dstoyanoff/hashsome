import type { MediaPlayerSource } from '@hashsome/core';

const SOURCE_WORDS = {
  playlist: 'Playlist',
  album: 'Album',
  artist: 'Artist',
  radio: 'Radio station',
} as const;

/** What a queue was filled from, for the queue's header: "Playlist · Daily Mix 2", or just the name when its kind is
 * not known. */
export function sourceLine(source: MediaPlayerSource): string {
  return source.kind ? `${SOURCE_WORDS[source.kind]} · ${source.title}` : source.title;
}

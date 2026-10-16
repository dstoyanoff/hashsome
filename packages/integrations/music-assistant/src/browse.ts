import type { AssetSize, BrowseItem, BrowseKind } from '@hashsome/core';

/** The fields of a Music Assistant media item that a library list needs. Items come back as plain
 * JSON; anything missing is simply left out of the result. */
export interface MaItem {
  uri?: string;
  name?: string;
  media_type?: string;
  artists?: { name?: string }[] | null;
  artist_str?: string | null;
  owner?: string | null;
  duration?: number | null;
  image?: MaImage | null;
  metadata?: { images?: MaImage[] | null } | null;
}

interface MaImage {
  path?: string;
  remotely_accessible?: boolean;

  /** What Music Assistant's own picture address (`/imageproxy/<proxy_id>`) is made from. */
  proxy_id?: string;
}

/** The top level: a shelf per way into the library. Their ids are not Music Assistant uris. */
export const SHELVES: { id: string; title: string; kind: BrowseKind; holds?: BrowseKind }[] = [
  { id: 'shelf:recent', title: 'Recently played', kind: 'folder' },
  { id: 'shelf:playlists', title: 'Playlists', kind: 'folder', holds: 'playlist' },
  { id: 'shelf:albums', title: 'Albums', kind: 'folder', holds: 'album' },
  { id: 'shelf:artists', title: 'Artists', kind: 'folder', holds: 'artist' },
  { id: 'shelf:radio', title: 'Radio', kind: 'folder', holds: 'radio' },
];

/** The kinds a media type maps to; the others are `other` and cannot be opened. */
const KINDS: Record<string, BrowseKind> = {
  album: 'album',
  artist: 'artist',
  playlist: 'playlist',
  track: 'track',
  radio: 'radio',
};

const EXPANDABLE = new Set(['album', 'artist', 'playlist']);

export function toBrowseItem(
  item: MaItem,
  assetUrl?: (path: string) => string,
): BrowseItem | undefined {
  if (typeof item.uri !== 'string' || typeof item.name !== 'string') {
    return undefined;
  }

  const type = item.media_type ?? '';
  const subtitle = item.artist_str ?? item.artists?.[0]?.name ?? item.owner ?? undefined;
  const artwork = artworkOf(item, assetUrl);
  return {
    id: item.uri,
    title: item.name,
    kind: KINDS[type] ?? 'other',
    playable: type in KINDS,
    expandable: EXPANDABLE.has(type),
    ...(subtitle ? { subtitle } : {}),
    ...(artwork ? { artworkUrl: artwork } : {}),
    ...(type === 'track' && typeof item.duration === 'number' ? { duration: item.duration } : {}),
  };
}

/** What the sizes a picture is asked for in are, in pixels: Music Assistant resizes to 80, 160, 256, 512 or 1024 only. A
 * `small` one is for a thumbnail (a list's row, about 44 px), a `medium` one for a cover (about 200 px), a `large` one for
 * a picture that fills a screen. The same picture weighs 4 kB at 80, 30 kB at 256 and 108 kB at 512. */
export const PICTURE_SIZES: Record<AssetSize, number> = { small: 80, medium: 256, large: 512 };

/** What a picture is asked for as when nothing says how big it will be drawn. */
export const PICTURE_SIZE = PICTURE_SIZES.medium;

/**
 * Where a browser gets an item's picture. Through the runtime (`assetUrl`) from Music Assistant's own picture address
 * when the item has the id of one, which the runtime fetches with the login, can resize to what is drawn and keeps,
 * and which a browser away from the server can reach. Without `assetUrl` or such an id, the picture's own address
 * when a browser can open it by itself (a streaming service's), else none.
 */
export function artworkOf(item: MaItem, assetUrl?: (path: string) => string): string | undefined {
  const image = item.image ?? item.metadata?.images?.[0];
  if (assetUrl && image?.proxy_id) {
    return assetUrl(`/imageproxy/${image.proxy_id}?size=${PICTURE_SIZE}&fmt=jpg`);
  }

  return image?.remotely_accessible && image.path && /^https?:\/\//.test(image.path)
    ? image.path
    : undefined;
}

/** `library://album/12` -> where to ask for what is inside it. */
export function parseUri(uri: string): { provider: string; type: string; id: string } {
  const match = /^([^:]+):\/\/([^/]+)\/(.+)$/.exec(uri);
  if (!match) {
    throw new Error(`"${uri}" is not a media item`);
  }

  return { provider: match[1]!, type: match[2]!, id: match[3]! };
}

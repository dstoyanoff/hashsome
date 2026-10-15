import type { IncomingMessage, ServerResponse } from 'node:http';
import { ASSET_PATH, ASSET_SIZES, type AssetSize, type Integration } from '@hashsome/core';

/** How long a browser may reuse a file whose address can give another one later. */
const CACHE_CONTROL = 'private, max-age=3600';

/** How long it may keep one whose address always gives the same file (`Integration.isStableAsset`). */
const CACHE_CONTROL_STABLE = 'private, max-age=31536000, immutable';

/** What the server itself keeps of the files it has fetched, so every display and phone showing the same picture
 * costs the backend one fetch, not one each. A picture is a few tens of kB; the limits are generous for that. */
const KEEP_BYTES = 64 * 1024 * 1024;
const KEEP_FILES = 500;
const KEEP_MS = 6 * 60 * 60 * 1000;

/** A file bigger than this is passed on but not kept. */
const KEEP_AT_MOST = 2 * 1024 * 1024;

interface Kept {
  type: string;
  body: Buffer;
  at: number;
}

/** The files kept, least recently used first (a `Map` keeps the order things were put in). */
const kept = new Map<string, Kept>();
let keptBytes = 0;

/** What is being fetched right now, so two requests for the same file at once wait for one fetch. */
const fetching = new Map<string, Promise<Fetched>>();

/** Forgets every file (for a test, or a backend that has been replaced). */
export function clearAssetCache(): void {
  kept.clear();
  fetching.clear();
  keptBytes = 0;
}

function remember(key: string, file: Kept): void {
  const before = kept.get(key);
  if (before) {
    keptBytes -= before.body.length;
    kept.delete(key);
  }

  kept.set(key, file);
  keptBytes += file.body.length;
  for (const [oldest, old] of kept) {
    if (keptBytes <= KEEP_BYTES && kept.size <= KEEP_FILES) {
      break;
    }

    kept.delete(oldest);
    keptBytes -= old.body.length;
  }
}

function recall(key: string, now: number): Kept | undefined {
  const file = kept.get(key);
  if (!file) {
    return undefined;
  }

  kept.delete(key);
  if (now - file.at > KEEP_MS) {
    keptBytes -= file.body.length;
    return undefined;
  }

  // Used just now: it goes to the end, so it is the last to be let go.
  kept.set(key, file);
  return file;
}

/** What fetching a file gave: the file, or why there is none (as the status to answer with). */
interface Fetched {
  status: number;
  file?: Kept;
}

/** One file from the integration: an image, or nothing. */
async function fetchImage(
  integration: Integration,
  path: string,
  size: AssetSize | undefined,
): Promise<Fetched> {
  const upstream = await (size === undefined
    ? integration.fetchAsset!(path)
    : integration.fetchAsset!(path, { size }));

  const type = upstream.headers.get('content-type') ?? '';
  if (!upstream.ok || !type.startsWith('image/')) {
    return { status: upstream.ok ? 415 : 404 };
  }

  return {
    status: 200,
    file: { type, body: Buffer.from(await upstream.arrayBuffer()), at: Date.now() },
  };
}

/**
 * Serves `GET /_hashsome/asset/<integration>?path=<backend path>`: a file one of an integration's
 * entities points at (artwork, a person's picture), fetched by the server with the integration's
 * credentials so the browser never needs to reach, trust or log in to the backend. Only image
 * responses are passed on, and the server keeps the ones it has fetched for a while (a few hours, a
 * bounded amount) so the next display to ask does not make the backend fetch it again. A browser may keep a file
 * for good when the integration says its address always gives the same one. Returns `false` for a
 * request that is not an asset request, so the caller can carry on.
 */
export async function serveAsset(
  integrations: Integration[],
  request: Pick<IncomingMessage, 'method' | 'url'>,
  response: ServerResponse,
): Promise<boolean> {
  const url = new URL(request.url ?? '/', 'http://localhost');
  if (!url.pathname.startsWith(`${ASSET_PATH}/`)) {
    return false;
  }

  const fail = (status: number, message: string) => {
    response.statusCode = status;
    response.setHeader('content-type', 'text/plain');
    response.end(message);
    return true;
  };

  if (request.method !== 'GET') {
    return fail(405, 'Method not allowed');
  }

  const id = decodeURIComponent(url.pathname.slice(ASSET_PATH.length + 1));
  const path = url.searchParams.get('path');
  const integration = integrations.find((candidate) => candidate.id === id);
  if (!integration?.fetchAsset || !path) {
    return fail(404, 'No such asset');
  }

  // How big it is wanted, if said and one of the sizes there are; anything else is ignored.
  const wanted = url.searchParams.get('size');
  const size = ASSET_SIZES.find((candidate) => candidate === wanted);
  const key = `${id}\u0000${path}\u0000${size ?? ''}`;
  try {
    let file = recall(key, Date.now());
    if (!file) {
      const waiting =
        fetching.get(key) ??
        fetchImage(integration, path, size)
          .then((result) => {
            if (result.file && result.file.body.length <= KEEP_AT_MOST) {
              remember(key, result.file);
            }

            return result;
          })
          .finally(() => fetching.delete(key));

      fetching.set(key, waiting);
      const result = await waiting;
      if (!result.file) {
        return fail(result.status, 'No such asset');
      }

      file = result.file;
    }

    response.statusCode = 200;
    response.setHeader('content-type', file.type);
    response.setHeader(
      'cache-control',
      integration.isStableAsset?.(path) ? CACHE_CONTROL_STABLE : CACHE_CONTROL,
    );

    response.end(file.body);
    return true;
  } catch {
    return fail(404, 'No such asset');
  }
}

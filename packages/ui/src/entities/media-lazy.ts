import { lazyExport } from '../layout/lazy.tsx';
import type { MediaBrowserProps } from './media-browser.tsx';
import type { MediaPlayerFullProps } from './media-player-full.tsx';
import type { MediaQueueProps } from './media-queue.tsx';

// The library, the queue and the full player are what a card opens in a drawer or an overlay, so the card takes them as they are
// first drawn instead of carrying them with the page. A page that draws one itself imports it directly.
export const MediaBrowser = lazyExport<MediaBrowserProps>(
  () => import('./media-browser.tsx'),
  'MediaBrowser',
);

export const MediaQueue = lazyExport<MediaQueueProps>(
  () => import('./media-queue.tsx'),
  'MediaQueue',
);

export const MediaPlayerFull = lazyExport<MediaPlayerFullProps>(
  () => import('./media-player-full.tsx'),
  'MediaPlayerFull',
);

import { loadMotion } from './motion/index.tsx';

// The bodies of drawers are fetched when first drawn (`lazyExport`); a test has them already, so drawing one takes a moment, not a download.
// The animation library too: a test in full motion has it as the app would once it has started.
export const preloaded = await Promise.all([
  loadMotion(),
  import('./layout/energy-chart.tsx'),
  import('./layout/history-section.tsx'),
  import('./layout/logbook-history.tsx'),
  import('./layout/sensor-history.tsx'),
  import('./entities/weather-forecast.tsx'),
  import('./entities/media-browser.tsx'),
  import('./entities/media-queue.tsx'),
  import('./entities/media-player-full.tsx'),
  import('./entities/media-speakers.tsx'),
]);

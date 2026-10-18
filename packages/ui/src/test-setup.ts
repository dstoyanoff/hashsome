// The bodies of drawers are fetched when first drawn (`lazyExport`); a test has them already, so drawing one takes a moment, not a download.
export const preloaded = await Promise.all([
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

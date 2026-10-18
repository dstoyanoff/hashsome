import { reactRouter } from '@react-router/dev/vite';
import { defineConfig } from 'vite';
import { hashsomeBuildId } from './build-id.ts';

/**
 * Sensible default, re-exported by the project's own `vite.config.ts` (the React Router plugin
 * requires a config file at the project root). Server settings and the dev proxy are supplied
 * inline by the CLI.
 *
 * A function, so every load of the config gets its own React Router plugin. The SPA build starts a
 * preview server that loads this config again in the same process; a plugin created once at import
 * would be shared, and the preview's `serve` would overwrite the build's state in it
 * ("Expected build manifest").
 */
export default defineConfig(() => ({
  plugins: [reactRouter(), hashsomeBuildId()],
  // React Router's SPA prerender starts a private preview server; pin it to IPv4 so it is
  // reachable in containers where `localhost` resolves to ::1.
  preview: { host: '127.0.0.1' },
  resolve: {
    // One copy of these across the runtime app and the instance's dashboards.
    dedupe: ['react', 'react-dom', 'react-router', '@hashsome/core', '@hashsome/ui'],
  },
  optimizeDeps: {
    // Vite's dependency scanner only follows *static* imports reachable from the HTML entry —
    // route modules are loaded dynamically by React Router's own router, not a plain `import`
    // statement it can see, so a dashboard's dependencies (e.g. the energy chart's `recharts`)
    // only surface once that route is actually visited. Without this, navigating to a dashboard
    // not yet visited in the current dev session triggers a fresh dependency pre-bundle mid-visit
    // — the page's own module graph update races a React Router navigation already in flight, and
    // the view can appear stuck (URL changed, nothing rendered) until the resulting full reload,
    // sometimes several seconds later. Pointing the scanner at every route module upfront (plus
    // `app/root.tsx`, which pulls in `HashsomeProvider`) lets it discover all of this at
    // server startup instead.
    entries: ['app/root.tsx', 'dashboards/**/*.{ts,tsx}'],
  },
}));

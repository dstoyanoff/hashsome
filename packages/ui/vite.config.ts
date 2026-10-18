import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Standalone dev server for the component gallery (`pnpm docs`) — entirely decoupled from
// `apps/runtime` and any project's own routing, since this documents `@hashsome/ui` itself.
//
// `pnpm docs:build` writes the gallery as a static site into `docs/dist` (what GitHub Pages
// serves). `DOCS_BASE` is the path it is served under: `/hash/` on Pages, `/` by default.
export default defineConfig({
  root: 'docs',
  base: process.env.DOCS_BASE ?? '/',
  plugins: [react()],
  build: { outDir: 'dist', emptyOutDir: true },
});

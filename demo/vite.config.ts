import base from '@hashsome/runtime/vite-config';
import { defineConfig, mergeConfig } from 'vite';

// The dashboards are the example's own, so Vite is pointed at them.
export default defineConfig(async (env) =>
  mergeConfig(await base(env), {
    base: process.env.DEMO_BASE ?? '/',
    optimizeDeps: { entries: ['../example/dashboards/**/*.{ts,tsx}'] },
  }),
);

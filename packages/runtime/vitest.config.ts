import { join } from 'node:path';
import { hashsomeIcons } from '@hashsome/ui/vite';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [
    hashsomeIcons({
      roots: [join(import.meta.dirname, 'app'), join(import.meta.dirname, '../ui/src')],
    }),
  ],
  test: { name: 'runtime', include: ['src/**/*.test.ts', 'app/**/*.test.tsx'] },
});

import { join } from 'node:path';
import { defineConfig, type Plugin } from 'vitest/config';
import { hashsomeIcons } from './src/vite/icons.ts';

/** jsdom's stylesheet parser does not know cascade layers, and drops what is inside one. The stylesheets are made of
 * `@layer name { ... }` blocks and one `@layer a, b;` statement, so for a test they are written without the layers: the
 * rules are the same, in the same order. */
function withoutLayers(): Plugin {
  return {
    name: 'hashsome:without-layers',
    enforce: 'pre',
    transform(code, id) {
      if (!id.endsWith('.css')) {
        return null;
      }

      let out = code.replace(/@layer [\w-]+(?:, ?[\w-]+)*;\n?/g, '');
      // Unwrap each `@layer name {` block: drop the opening and its matching closing brace.
      for (;;) {
        const open = out.search(/@layer [\w-]+ \{/);
        if (open === -1) {
          return out;
        }

        const start = out.indexOf('{', open);
        let depth = 0;
        let end = start;
        for (; end < out.length; end++) {
          depth += out[end] === '{' ? 1 : out[end] === '}' ? -1 : 0;
          if (depth === 0) {
            break;
          }
        }

        out = out.slice(0, open) + out.slice(start + 1, end) + out.slice(end + 1);
      }
    },
  };
}

export default defineConfig({
  plugins: [withoutLayers(), hashsomeIcons({ roots: [join(import.meta.dirname, 'src')] })],
  test: {
    name: 'ui',
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test-setup.ts'],
    css: true,
  },
});

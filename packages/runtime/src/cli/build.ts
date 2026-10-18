import { resolve } from 'node:path';
import { createBuilder } from 'vite';
import { precompress } from '../server/precompress.ts';
import { loadConfig } from '../config.ts';
import { createViteConfig } from '../vite.ts';

/** Builds the client into `build/client`. Exits when done, unless `exit` is false (a caller that has more to do). */
export async function build({ exit = true }: { exit?: boolean } = {}) {
  const config = await loadConfig(resolve(process.cwd()));
  const inline = createViteConfig(config);
  // React Router's SPA prerender step starts a Vite preview server that resolves the
  // project from the working directory.
  process.chdir(inline.root ?? config.root);
  const builder = await createBuilder(inline);
  await builder.buildApp();
  // Written beside the files, for the server to send as they are (see `precompress`).
  precompress(resolve(inline.root ?? config.root, 'build', 'client'));
  // Vite/React Router leave handles open after the SPA prerender; nothing else to wait for.
  if (exit) {
    process.exit(0);
  }
}

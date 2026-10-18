/** `tsc` leaves the stylesheets the components import behind: this copies every `.css` file from `src` to the same place in `dist`. */
import { cpSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

const src = join(import.meta.dirname, '..', 'src');
const dist = join(import.meta.dirname, '..', 'dist');

function* files(dir: string): Generator<string> {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* files(path);
    } else if (entry.name.endsWith('.css')) {
      yield path;
    }
  }
}

for (const file of files(src)) {
  cpSync(file, join(dist, relative(src, file)));
}

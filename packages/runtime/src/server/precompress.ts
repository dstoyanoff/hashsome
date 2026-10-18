import { brotliCompressSync, constants, gzipSync } from 'node:zlib';
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { extname, join } from 'node:path';

/** What is worth compressing: text. Pictures, fonts and the like are compressed already. */
const COMPRESSIBLE = new Set([
  '.js',
  '.mjs',
  '.css',
  '.json',
  '.svg',
  '.html',
  '.txt',
  '.webmanifest',
]);

/** Under this a file is not worth the extra request-time work or the bytes on disk. */
const MIN_BYTES = 1024;

/** The compressed copies written next to a file, and the `Content-Encoding` each is for, best first. */
export const VARIANTS = [
  { encoding: 'br', extension: '.br' },
  { encoding: 'gzip', extension: '.gz' },
] as const;

function* files(dir: string): Generator<string> {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) {
      yield* files(path);
    } else if (COMPRESSIBLE.has(extname(entry.name)) && statSync(path).size >= MIN_BYTES) {
      yield path;
    }
  }
}

/**
 * Writes a Brotli and a gzip copy next to every compressible file of `dir` (`app.js` gets `app.js.br` and `app.js.gz`), at build
 * time, so the server never compresses anything while a display waits: it sends the copy the browser accepts.
 * Returns how many files it did.
 */
export function precompress(dir: string): number {
  let count = 0;
  for (const file of files(dir)) {
    const body = readFileSync(file);
    writeFileSync(
      `${file}.br`,
      brotliCompressSync(body, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }),
    );

    writeFileSync(`${file}.gz`, gzipSync(body, { level: 9 }));
    count += 1;
  }

  return count;
}

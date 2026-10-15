import { mkdtempSync, writeFileSync } from 'node:fs';
import type { Server } from 'node:http';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';
import { VERSION_FILE, VERSION_PATH } from '../../build-id.ts';
import { createApp } from '../production.ts';

let server: Server | undefined;
afterEach(() => new Promise((done) => (server ? server.close(done) : done(undefined))));

async function get(dir: string, path: string) {
  server = createServer(createApp(dir));
  await new Promise<void>((ready) => server!.listen(0, '127.0.0.1', ready));
  return fetch(`http://127.0.0.1:${(server.address() as AddressInfo).port}${path}`);
}

const clientDir = (version?: string) => {
  const dir = mkdtempSync(join(tmpdir(), 'hashsome-client-'));
  writeFileSync(join(dir, 'index.html'), '<html></html>');
  writeFileSync(join(dir, 'icon.png'), 'png');
  if (version !== undefined) {
    writeFileSync(join(dir, VERSION_FILE), version);
  }

  return dir;
};

test('tells which build it serves, and never lets that answer be cached', async () => {
  const response = await get(clientDir('{"id":"abc123"}'), VERSION_PATH);
  expect(response.status).toBe(200);
  expect(response.headers.get('cache-control')).toBe('no-store');
  expect(await response.json()).toEqual({ id: 'abc123' });
});

test('has no answer for a client built without an id, or with a broken file', async () => {
  expect((await get(clientDir(), VERSION_PATH)).status).toBe(404);
  await new Promise((done) => server!.close(done));
  expect((await get(clientDir('not json'), VERSION_PATH)).status).toBe(404);
});

test('a page that is not a file gets the app, which routes it on its own', async () => {
  const dir = clientDir();
  for (const path of ['/', '/phone', '/phone/rooms']) {
    const response = await get(dir, path);
    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toMatch(/html/);
    expect(await response.text()).toBe('<html></html>');
    await new Promise((done) => server!.close(done));
  }
});

test('a file that is there is served, and one that is not is a 404, never the app’s page', async () => {
  const dir = clientDir();
  const there = await get(dir, '/icon.png');
  expect(there.status).toBe(200);
  expect(await there.text()).toBe('png');
  await new Promise((done) => server!.close(done));

  // iOS asks for these two before it reads a page's icon link: HTML with a 200 is taken for the icon.
  for (const path of [
    '/apple-touch-icon.png',
    '/apple-touch-icon-precomposed.png',
    '/missing.js',
  ]) {
    const missing = await get(dir, path);
    expect(missing.status).toBe(404);
    expect(await missing.text()).toBe('');
    await new Promise((done) => server!.close(done));
  }
});

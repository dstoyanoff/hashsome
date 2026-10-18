import { existsSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import type { Server } from 'node:http';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';
import { VERSION_FILE, VERSION_PATH } from '../../build-id.ts';
import { precompress } from '../precompress.ts';
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

test('a favicon is served when the project has one, and is a 404 (not an empty success) when it has not', async () => {
  const dir = clientDir();
  writeFileSync(join(dir, 'favicon.ico'), 'ico');
  const there = await get(dir, '/favicon.ico');
  expect(there.status).toBe(200);
  expect(await there.text()).toBe('ico');
  await new Promise((done) => server!.close(done));

  // An empty 204 reads as "the icon is empty" to a browser that is picking an icon for the page.
  const missing = await get(clientDir(), '/favicon.ico');
  expect(missing.status).toBe(404);
});

test('the build’s own files are kept for good, the page and the other files are asked for again', async () => {
  const dir = clientDir();
  mkdirSync(join(dir, 'assets'));
  writeFileSync(join(dir, 'assets', 'app-abc123.js'), 'console.log(1)');
  const asset = await get(dir, '/assets/app-abc123.js');
  expect(asset.headers.get('cache-control')).toBe('public, max-age=31536000, immutable');
  await new Promise((done) => server!.close(done));

  const page = await get(dir, '/index.html');
  expect(page.headers.get('cache-control')).toBe('no-cache');
  await new Promise((done) => server!.close(done));
  expect((await get(dir, '/icon.png')).headers.get('cache-control')).toBe('public, max-age=3600');
});

test('a file with compressed copies is sent as the best one the browser accepts, with the right type', async () => {
  const dir = clientDir();
  mkdirSync(join(dir, 'assets'));
  const body = 'console.log("hello");'.repeat(200);
  writeFileSync(join(dir, 'assets', 'app-abc123.js'), body);
  expect(precompress(dir)).toBe(1);

  const ask = async (encoding: string) => {
    const response = await fetch(
      `http://127.0.0.1:${(server!.address() as AddressInfo).port}/assets/app-abc123.js`,
      {
        headers: { 'accept-encoding': encoding },
      },
    );

    return { response, text: await response.text() };
  };

  server = createServer(createApp(dir));
  await new Promise<void>((ready) => server!.listen(0, '127.0.0.1', ready));
  for (const [accepted, sent] of [
    ['gzip, deflate, br', 'br'],
    ['gzip', 'gzip'],
    ['identity', null],
  ] as const) {
    const { response, text } = await ask(accepted);
    expect(response.headers.get('content-encoding')).toBe(sent);
    expect(response.headers.get('content-type')).toMatch(/javascript/);
    expect(response.headers.get('cache-control')).toBe('public, max-age=31536000, immutable');
    expect(response.headers.get('vary')).toBe('Accept-Encoding');
    expect(text).toBe(body);
  }
});

test('only text over a kilobyte is worth compressing', () => {
  const dir = clientDir();
  mkdirSync(join(dir, 'assets'));
  writeFileSync(join(dir, 'assets', 'small.js'), 'x');
  writeFileSync(join(dir, 'assets', 'font.woff2'), 'x'.repeat(5000));
  writeFileSync(join(dir, 'assets', 'big.css'), 'a{}'.repeat(1000));
  expect(precompress(dir)).toBe(1);
  expect(existsSync(join(dir, 'assets', 'big.css.br'))).toBe(true);
  expect(existsSync(join(dir, 'assets', 'big.css.gz'))).toBe(true);
  expect(existsSync(join(dir, 'assets', 'small.js.br'))).toBe(false);
  expect(existsSync(join(dir, 'assets', 'font.woff2.br'))).toBe(false);
});

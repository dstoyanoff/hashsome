import type { ServerResponse } from 'node:http';
import type { Integration } from '@hashsome/core';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { clearAssetCache, serveAsset } from '../assets.ts';

beforeEach(clearAssetCache);
afterEach(() => vi.useRealTimers());

function fakeResponse() {
  const headers: Record<string, string> = {};
  const res = {
    statusCode: 0,
    setHeader: (name: string, value: string) => void (headers[name] = value),
    end: vi.fn<(body?: unknown) => void>(),
  };

  return { res, headers, asResponse: res as unknown as ServerResponse };
}

const integration = (
  fetchAsset?: (path: string) => Promise<Response>,
  isStableAsset?: (path: string) => boolean,
) =>
  ({
    id: 'ha',
    ...(fetchAsset ? { fetchAsset } : {}),
    ...(isStableAsset ? { isStableAsset } : {}),
  }) as unknown as Integration;

const image = () =>
  new Response(new Uint8Array([1, 2, 3]), { headers: { 'content-type': 'image/jpeg' } });

test('passes through a request that is not an asset request', async () => {
  const { asResponse } = fakeResponse();
  expect(await serveAsset([], { method: 'GET', url: '/ws' }, asResponse)).toBe(false);
});

test("serves an integration's image with a cache header", async () => {
  const fetchAsset = vi.fn<(path: string) => Promise<Response>>(async () => image());
  const { res, headers, asResponse } = fakeResponse();
  const served = await serveAsset(
    [integration(fetchAsset)],
    { method: 'GET', url: `/_hashsome/asset/ha?path=${encodeURIComponent('/api/x?token=1')}` },
    asResponse,
  );

  expect(served).toBe(true);
  expect(fetchAsset).toHaveBeenCalledWith('/api/x?token=1');
  expect(res.statusCode).toBe(200);
  expect(headers['content-type']).toBe('image/jpeg');
  expect(headers['cache-control']).toContain('max-age');
});

test('refuses anything that is not an image, a failed fetch, an unknown integration or a POST', async () => {
  const cases: [Integration[], string, string][] = [
    [
      [
        integration(
          async () => new Response('{}', { headers: { 'content-type': 'application/json' } }),
        ),
      ],
      'GET',
      '/_hashsome/asset/ha?path=%2Fa',
    ],
    [
      [
        integration(
          async () => new Response('x', { status: 404, headers: { 'content-type': 'image/png' } }),
        ),
      ],
      'GET',
      '/_hashsome/asset/ha?path=%2Fa',
    ],
    [
      [
        integration(async () => {
          throw new Error('refused');
        }),
      ],
      'GET',
      '/_hashsome/asset/ha?path=%2Fa',
    ],
    [[integration(async () => image())], 'GET', '/_hashsome/asset/other?path=%2Fa'],
    [[integration()], 'GET', '/_hashsome/asset/ha?path=%2Fa'],
    [[integration(async () => image())], 'GET', '/_hashsome/asset/ha'],
    [[integration(async () => image())], 'POST', '/_hashsome/asset/ha?path=%2Fa'],
  ];

  for (const [integrations, method, url] of cases) {
    const { res, asResponse } = fakeResponse();
    expect(await serveAsset(integrations, { method, url }, asResponse)).toBe(true);
    expect(res.statusCode).toBeGreaterThanOrEqual(404);
  }
});

const get = (integrations: Integration[], path: string) => {
  const served = fakeResponse();
  const url = `/_hashsome/asset/ha?path=${encodeURIComponent(path)}`;
  return serveAsset(integrations, { method: 'GET', url }, served.asResponse).then(() => served);
};

test('a file fetched once is kept, so the next request does not ask the backend again', async () => {
  const fetchAsset = vi.fn<(path: string) => Promise<Response>>(async () => image());
  const integrations = [integration(fetchAsset)];
  const first = await get(integrations, '/a');
  const second = await get(integrations, '/a');
  const other = await get(integrations, '/b');
  expect(fetchAsset).toHaveBeenCalledTimes(2);
  expect(first.res.statusCode).toBe(200);
  expect(second.res.statusCode).toBe(200);
  expect(second.headers['content-type']).toBe('image/jpeg');
  expect(second.res.end).toHaveBeenCalledWith(Buffer.from([1, 2, 3]));
  expect(other.res.statusCode).toBe(200);
});

test('two requests for the same file at once wait for one fetch', async () => {
  let finish: (response: Response) => void = () => undefined;
  const fetchAsset = vi.fn<(path: string) => Promise<Response>>(
    () => new Promise((resolve) => (finish = resolve)),
  );

  const integrations = [integration(fetchAsset)];
  const both = Promise.all([get(integrations, '/a'), get(integrations, '/a')]);
  await Promise.resolve();
  finish(image());
  const [one, two] = await both;
  expect(fetchAsset).toHaveBeenCalledTimes(1);
  expect(one.res.statusCode).toBe(200);
  expect(two.res.statusCode).toBe(200);
});

test('a kept file is let go after a few hours, and a failure is not kept', async () => {
  vi.useFakeTimers({ now: new Date('2026-06-01T12:00:00Z') });
  const fetchAsset = vi.fn<(path: string) => Promise<Response>>(async () => image());
  const integrations = [integration(fetchAsset)];
  await get(integrations, '/a');
  vi.setSystemTime(new Date('2026-06-01T17:00:00Z'));
  await get(integrations, '/a');
  expect(fetchAsset).toHaveBeenCalledTimes(1);
  vi.setSystemTime(new Date('2026-06-01T19:00:00Z'));
  await get(integrations, '/a');
  expect(fetchAsset).toHaveBeenCalledTimes(2);

  const failing = vi.fn<(path: string) => Promise<Response>>(
    async () => new Response('x', { status: 404, headers: { 'content-type': 'image/png' } }),
  );

  await get([integration(failing)], '/gone');
  await get([integration(failing)], '/gone');
  expect(failing).toHaveBeenCalledTimes(2);
});

test('the server keeps a bounded number of files, letting go of the one used longest ago', async () => {
  const fetchAsset = vi.fn<(path: string) => Promise<Response>>(async () => image());
  const integrations = [integration(fetchAsset)];
  for (let n = 0; n < 500; n++) {
    await get(integrations, `/f${n}`);
  }

  await get(integrations, '/f0');
  expect(fetchAsset).toHaveBeenCalledTimes(500);
  // One more than it keeps: the oldest of what was fetched goes.
  await get(integrations, '/new');
  await get(integrations, '/f1');
  expect(fetchAsset).toHaveBeenCalledTimes(502);
  await get(integrations, '/f0');
  expect(fetchAsset).toHaveBeenCalledTimes(502);
});

test('a browser keeps a file for good only when its address always gives the same file', async () => {
  const integrations = [
    integration(
      async () => image(),
      (path) => path.startsWith('/stable/'),
    ),
  ];

  const stable = await get(integrations, '/stable/abc');
  const other = await get(integrations, '/changing');

  expect(stable.headers['cache-control']).toContain('immutable');
  expect(stable.headers['cache-control']).toContain('max-age=31536000');
  expect(other.headers['cache-control']).toBe('private, max-age=3600');
});

test('the size asked for goes to the integration, and a file of another size is another file', async () => {
  const fetchAsset = vi.fn<(path: string, options?: { size?: string }) => Promise<Response>>(
    async () => image(),
  );

  const integrations = [integration(fetchAsset as never)];
  const ask = (size: string) => {
    const served = fakeResponse();
    const url = `/_hashsome/asset/ha?path=%2Fa&size=${size}`;
    return serveAsset(integrations, { method: 'GET', url }, served.asResponse);
  };

  await ask('small');
  await ask('small');
  await ask('medium');
  expect(fetchAsset).toHaveBeenCalledTimes(2);
  expect(fetchAsset).toHaveBeenCalledWith('/a', { size: 'small' });
  expect(fetchAsset).toHaveBeenCalledWith('/a', { size: 'medium' });

  // Anything that is not one of the sizes there are is not passed on.
  await ask('80');
  await ask('huge');
  expect(fetchAsset).toHaveBeenCalledWith('/a');
  expect(fetchAsset).not.toHaveBeenCalledWith('/a', { size: 'huge' });
});

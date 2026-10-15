import { mkdtempSync, writeFileSync } from 'node:fs';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, expect, test } from 'vitest';
import { resolveConfig } from '../../config.ts';
import { HA_COMPAT_VERSION } from '../ha-compat.ts';
import { createApp } from '../production.ts';

let server: Server | undefined;
afterEach(() => new Promise((done) => (server ? server.close(done) : done(undefined))));

async function start(compat: boolean) {
  const dir = mkdtempSync(join(tmpdir(), 'hashsome-compat-'));
  writeFileSync(join(dir, 'index.html'), '<html>the app</html>');
  server = createServer(createApp(dir, [], { homeAssistantCompat: compat }));
  await new Promise<void>((ready) => server!.listen(0, '127.0.0.1', ready));
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const json = (path: string, init?: RequestInit) =>
    fetch(`${base}${path}`, init).then((response) => response.json() as Promise<any>);

  return { base, json };
}

const post = (body: unknown, type = 'application/json') => ({
  method: 'POST',
  headers: { 'content-type': type },
  body: typeof body === 'string' ? body : JSON.stringify(body),
});

test('walks a client through Home Assistant login: a form, an entry with a code, a token', async () => {
  const { json } = await start(true);

  const form = await json(
    '/auth/login_flow',
    post({
      client_id: 'https://home-assistant.io/android',
      handler: ['homeassistant', null],
      redirect_uri: 'homeassistant://auth-callback',
    }),
  );

  expect(form).toMatchObject({ type: 'form', step_id: 'init', handler: ['homeassistant', null] });
  expect(form.data_schema.map((field: { name: string }) => field.name)).toEqual([
    'username',
    'password',
  ]);

  const entry = await json(
    `/auth/login_flow/${form.flow_id}`,
    post({ username: 'anyone', password: 'anything', client_id: 'c' }),
  );

  expect(entry).toMatchObject({ type: 'create_entry', flow_id: form.flow_id });
  expect(entry.result).toMatch(/^[0-9a-f]{32}$/);

  const token = await json(
    '/auth/token',
    post(
      `grant_type=authorization_code&code=${entry.result}&client_id=c`,
      'application/x-www-form-urlencoded',
    ),
  );

  expect(token).toMatchObject({ token_type: 'Bearer', expires_in: 1800 });
  expect(token.access_token).toBeTruthy();
  expect(token.refresh_token).toBeTruthy();
});

test('answers the identity checks: providers, the API, its config and discovery', async () => {
  const { json } = await start(true);
  expect((await json('/auth/providers')).providers[0]).toMatchObject({ type: 'homeassistant' });
  expect(await json('/api/')).toEqual({ message: 'API running.' });
  expect(await json('/api/config')).toMatchObject({ version: HA_COMPAT_VERSION, state: 'RUNNING' });
  expect(await json('/api/discovery_info')).toMatchObject({
    version: HA_COMPAT_VERSION,
    requires_api_password: false,
  });
});

test('serves a Home Assistant manifest naming the companion app', async () => {
  const { base } = await start(true);
  const response = await fetch(`${base}/manifest.json`);
  expect(response.headers.get('content-type')).toContain('application/manifest+json');
  expect(await response.json()).toMatchObject({
    name: 'Home Assistant',
    related_applications: [{ id: 'io.homeassistant.companion.android' }],
  });
});

test('leaves everything else to the app', async () => {
  const { base } = await start(true);
  expect(await (await fetch(`${base}/bathroom`)).text()).toContain('the app');
  expect((await fetch(`${base}/healthz`)).status).toBe(200);
});

test('does nothing unless it is turned on', async () => {
  const { base } = await start(false);
  const response = await fetch(`${base}/auth/login_flow`, post({}));
  expect(response.headers.get('content-type')).not.toContain('application/json');
  expect(await response.text()).toContain('the app');
  // Not the compat manifest, and, being a file that is not there, not the app's page either.
  expect((await fetch(`${base}/manifest.json`)).status).toBe(404);
});

test('is off in a config that does not ask for it', () => {
  expect(resolveConfig('/p', {}).homeAssistantCompat).toBe(false);
  expect(resolveConfig('/p', { homeAssistantCompat: true }).homeAssistantCompat).toBe(true);
});

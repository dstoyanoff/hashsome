import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { RemoteClient } from '../client.ts';
import type { EntityRef } from '../entity.ts';
import type { Entity } from '../model/index.ts';

class FakeSocket extends EventTarget {
  static instances: FakeSocket[] = [];
  readyState = 0;
  sent: unknown[] = [];
  constructor(public url: string) {
    super();
    FakeSocket.instances.push(this);
  }
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  close() {
    this.readyState = 3;
    this.dispatchEvent(new Event('close'));
  }
  open() {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  receive(message: unknown) {
    this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(message) }));
  }
}

const lamp = 'ha:light.lamp' as EntityRef;
const entity = {
  ref: lamp,
  kind: 'light',
  name: 'Lamp',
  availability: 'ready',
  on: true,
  capabilities: { brightness: false, colorTemperature: false, color: false },
} as Entity;

function make() {
  FakeSocket.instances = [];
  const client = new RemoteClient({
    url: 'ws://x/ws',
    createSocket: (url) => new FakeSocket(url) as unknown as WebSocket,
    reconnectMinMs: 100,
  });

  client.connect();
  return { client, socket: () => FakeSocket.instances.at(-1)! };
}

beforeEach(() => vi.useFakeTimers());
afterEach(() => vi.useRealTimers());

test('subscribes once per ref and delivers the entity', () => {
  const { client, socket } = make();
  socket().open();
  const a = vi.fn<(s: Entity | null | undefined) => void>();
  const b = vi.fn<(s: Entity | null | undefined) => void>();
  client.subscribe(lamp, a);
  client.subscribe(lamp, b);
  expect(socket().sent).toEqual([{ type: 'subscribe', ref: lamp }]);
  socket().receive({ type: 'entity', ref: lamp, entity });
  expect(a).toHaveBeenLastCalledWith(entity);
  expect(b).toHaveBeenLastCalledWith(entity);
});

test('unsubscribes when last listener leaves', () => {
  const { client, socket } = make();
  socket().open();
  const off = client.subscribe(lamp, () => {});
  off();
  expect(socket().sent.at(-1)).toEqual({ type: 'unsubscribe', ref: lamp });
});

test('reconnects with backoff and resubscribes', () => {
  const { client, socket } = make();
  socket().open();
  client.subscribe(lamp, () => {});
  socket().close();
  expect(client.link).toBe('closed');
  vi.advanceTimersByTime(100);
  expect(FakeSocket.instances).toHaveLength(2);
  socket().open();
  expect(socket().sent).toEqual([{ type: 'subscribe', ref: lamp }]);
});

test('commands and raw requests resolve and reject from results', async () => {
  const { client, socket } = make();
  socket().open();
  const ok = client.command(lamp, 'toggle');
  expect(socket().sent.at(-1)).toEqual({ type: 'command', id: 1, ref: lamp, command: 'toggle' });
  socket().receive({ type: 'result', id: 1, ok: true });
  await expect(ok).resolves.toBeUndefined();
  const bad = client.command(lamp, 'setBrightness', { brightness: 1 });
  socket().receive({ type: 'result', id: 2, ok: false, error: 'nope' });
  await expect(bad).rejects.toThrow('nope');
  const raw = client.callRaw('ha', { thing: 1 });
  socket().receive({ type: 'result', id: 3, ok: true, data: { answer: 42 } });
  await expect(raw).resolves.toEqual({ answer: 42 });
});

test('tracks integration status and does not reconnect after close()', () => {
  const { client, socket } = make();
  socket().open();
  socket().receive({ type: 'status', integration: 'ha', status: 'connected' });
  expect(client.getIntegrationStatus('ha')).toBe('connected');
  client.close();
  vi.advanceTimersByTime(60_000);
  expect(FakeSocket.instances).toHaveLength(1);
});

test('browse sends a query and resolves with the library level it is answered with', async () => {
  const { client, socket } = make();
  socket().open();
  const level = client.browse(lamp, { path: 'shelf:albums', search: undefined as never });
  expect(socket().sent.at(-1)).toMatchObject({
    type: 'query',
    id: 1,
    ref: lamp,
    query: 'browse',
    args: { path: 'shelf:albums' },
  });

  socket().receive({ type: 'result', id: 1, ok: true, data: { items: [] } });
  await expect(level).resolves.toEqual({ items: [] });
  const failed = client.browse(lamp, {});
  socket().receive({ type: 'result', id: 2, ok: false, error: 'no library' });
  await expect(failed).rejects.toThrow('no library');
});

test('a read made while the socket is still opening waits for it and is answered', async () => {
  const { client, socket } = make();
  // The page asked the moment it appeared, before the connection was open.
  const library = client.browse(lamp, {});
  expect(socket().sent).toEqual([]);
  socket().open();
  await vi.advanceTimersByTimeAsync(0);
  expect(socket().sent.at(-1)).toMatchObject({ type: 'query', query: 'browse' });
  socket().receive({ type: 'result', id: 1, ok: true, data: { items: [] } });
  await expect(library).resolves.toEqual({ items: [] });
});

test('every kind of read waits, and a read made while it is reconnecting is sent once it is back', async () => {
  const { client, socket } = make();
  socket().open();
  socket().close();
  // Dropped: a new socket is on its way after the backoff.
  const history = client.history(lamp, { range: '1d' });
  const forecast = client.forecast(lamp, { type: 'daily' });
  const logbook = client.logbook(lamp, {});
  const queue = client.queue(lamp, {});
  await vi.advanceTimersByTimeAsync(150);
  socket().open();
  await vi.advanceTimersByTimeAsync(0);
  const sent = socket().sent as { query: string; id: number }[];
  expect(sent.map((message) => message.query)).toEqual(['history', 'forecast', 'logbook', 'queue']);
  for (const message of sent) {
    socket().receive({ type: 'result', id: message.id, ok: true, data: {} });
  }

  await expect(Promise.all([history, forecast, logbook, queue])).resolves.toHaveLength(4);
});

test('a read gives up when the connection does not come, and the caller can ask again', async () => {
  const { client } = make();
  const outcome = client.browse(lamp, {}).catch((error: Error) => error);
  await vi.advanceTimersByTimeAsync(10_500);
  expect(await outcome).toMatchObject({ message: 'Not connected to runtime' });
});

test('a command never waits: made while the connection is down it fails at once, not late', async () => {
  const { client, socket } = make();
  await expect(client.command(lamp, 'toggle')).rejects.toThrow('Not connected to runtime');
  await expect(client.callRaw('ha', {})).rejects.toThrow('Not connected to runtime');
  socket().open();
  // Nothing was queued to go out now that it is open.
  expect(socket().sent).toEqual([]);
});

test('closing the client gives up the reads that are waiting', async () => {
  const { client } = make();
  const outcome = client.browse(lamp, {}).catch((error: Error) => error);
  client.close();
  expect(await outcome).toMatchObject({ message: 'Connection closed' });
});

test('asks the runtime whether it is there every few seconds while the connection is open', () => {
  const { socket } = make();
  expect(socket().sent).toEqual([]);
  socket().open();
  vi.advanceTimersByTime(5_000);
  vi.advanceTimersByTime(5_000);
  expect(socket().sent.filter((m) => (m as { type: string }).type === 'ping')).toHaveLength(2);
});

test('a connection that has gone silent after the runtime answered is taken to be dead and opened again', () => {
  const { client, socket } = make();
  const first = socket();
  first.open();
  const links: string[] = [];
  client.onLinkChange((status) => links.push(status));
  first.receive({ type: 'pong' });
  // The other end goes away without a word: no close, no answer.
  vi.advanceTimersByTime(20_000);

  expect(links).toContain('closed');
  expect(client.link).not.toBe('open');
  // It opens another, after the pause every reconnect has.
  vi.advanceTimersByTime(1_000);
  expect(socket()).not.toBe(first);
});

test('a connection that keeps answering is left open however long it goes', () => {
  const { client, socket } = make();
  socket().open();
  for (let i = 0; i < 24; i++) {
    socket().receive({ type: 'pong' });
    vi.advanceTimersByTime(5_000);
  }

  expect(client.link).toBe('open');
  expect(FakeSocket.instances).toHaveLength(1);
});

test('a runtime that never answers a beat (an older one) is not taken for dead when it is quiet', () => {
  const { client, socket } = make();
  socket().open();
  vi.advanceTimersByTime(120_000);
  expect(client.link).toBe('open');
  expect(FakeSocket.instances).toHaveLength(1);
});

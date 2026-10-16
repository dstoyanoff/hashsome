import {
  mockLibrary,
  mockLight,
  mockMediaPlayer,
  mockSensor,
  mockWeather,
  MockIntegration,
  type ForecastQuery,
  type LogbookQuery,
  type QueueQuery,
  type ServerMessage,
} from '@hashsome/core';
import { describe, expect, test } from 'vitest';
import { Proxy, type ProxySocket } from '../proxy.ts';

class FakeSocket implements ProxySocket {
  readyState = 1;
  sent: ServerMessage[] = [];
  #handlers: Record<string, (data: { toString(): string }) => void> = {};
  send(data: string) {
    this.sent.push(JSON.parse(data));
  }
  on(event: 'message', listener: (data: { toString(): string }) => void): void;
  on(event: 'close' | 'error', listener: () => void): void;
  on(event: string, handler: (data: { toString(): string }) => void): void {
    this.#handlers[event] = handler;
  }
  receive(message: unknown) {
    this.#handlers.message?.({ toString: () => JSON.stringify(message) });
  }
  close() {
    this.readyState = 3;
    this.#handlers.close?.({ toString: () => '' });
  }
}

async function setup(connected = true) {
  const ha = new MockIntegration({ entities: { lamp: mockLight({ on: false }) } });
  if (connected) {
    await ha.connect();
  }

  const logs: string[] = [];
  const proxy = new Proxy([ha], { log: (m) => logs.push(m) });
  const socket = new FakeSocket();
  proxy.handleConnection(socket);
  return { ha, socket, logs };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('Proxy', () => {
  test('reports integration status on connect', async () => {
    const { socket } = await setup();
    expect(socket.sent).toContainEqual({ type: 'status', integration: 'ha', status: 'connected' });
  });

  test('subscribe replays the current entity and streams changes, with the local id', async () => {
    const { ha, socket } = await setup();
    socket.receive({ type: 'subscribe', ref: 'ha:lamp' });
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'entity',
      ref: 'ha:lamp',
      entity: { on: false },
    });

    ha.update('lamp', { on: true });
    expect(socket.sent.at(-1)).toMatchObject({ type: 'entity', entity: { on: true } });
  });

  test('a subscription waits for the integration to connect, then resolves', async () => {
    const { ha, socket } = await setup(false);
    socket.receive({ type: 'subscribe', ref: 'ha:lamp' });
    expect(socket.sent.some((m) => m.type === 'entity')).toBe(false);
    await ha.connect();
    expect(socket.sent.at(-1)).toMatchObject({ type: 'entity', ref: 'ha:lamp' });
  });

  test('an unknown integration or entity yields null and is logged', async () => {
    const { socket, logs } = await setup();
    socket.receive({ type: 'subscribe', ref: 'zz:lamp' });
    expect(socket.sent.at(-1)).toEqual({ type: 'entity', ref: 'zz:lamp', entity: null });
    socket.receive({ type: 'subscribe', ref: 'ha:nope' });
    expect(socket.sent.at(-1)).toEqual({ type: 'entity', ref: 'ha:nope', entity: null });
    expect(logs).toHaveLength(2);
  });

  test('commands route to the integration by local id and acknowledge', async () => {
    const { ha, socket } = await setup();
    socket.receive({ type: 'command', id: 7, ref: 'ha:lamp', command: 'toggle' });
    await settle();
    expect(ha.getEntity('lamp')).toMatchObject({ on: true });
    expect(ha.calls).toEqual([{ entityId: 'lamp', command: 'toggle' }]);
    expect(socket.sent.at(-1)).toEqual({ type: 'result', id: 7, ok: true });
  });

  test('command failures and unknown integrations return errors', async () => {
    const { ha, socket } = await setup();
    ha.command = () => Promise.reject(new Error('boom'));
    socket.receive({ type: 'command', id: 1, ref: 'ha:lamp', command: 'toggle' });
    await settle();
    expect(socket.sent.at(-1)).toEqual({ type: 'result', id: 1, ok: false, error: 'boom' });
    socket.receive({ type: 'command', id: 2, ref: 'nope:lamp', command: 'toggle' });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 2, ok: false });
  });

  test('raw requests need an integration that supports them', async () => {
    const { socket } = await setup();
    socket.receive({ type: 'raw', id: 3, integration: 'ha', request: {} });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 3, ok: false });
  });

  test('ignores malformed frames and cleans up on close', async () => {
    const { ha, socket } = await setup();
    socket.receive('garbage');
    socket.receive({ type: 'subscribe', ref: 'ha:lamp' });
    const count = socket.sent.length;
    socket.close();
    ha.update('lamp', { on: true });
    expect(socket.sent).toHaveLength(count);
  });
});

describe('browse queries', () => {
  async function library() {
    const ha = new MockIntegration({
      entities: { room: mockMediaPlayer({ capabilities: { browse: true } }) },
      library: mockLibrary(),
    });

    await ha.connect();
    const proxy = new Proxy([ha]);
    const socket = new FakeSocket();
    proxy.handleConnection(socket);
    return { socket };
  }

  test('a browse query is answered with the library level', async () => {
    const { socket } = await library();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:room',
      query: 'browse',
      args: { path: 'playlists', evil: 'dropped' },
    });

    await settle();
    expect(socket.sent.at(-1)).toMatchObject({
      type: 'result',
      id: 1,
      ok: true,
      data: { title: 'Playlists' },
    });
  });

  test('only string path and search reach the integration', async () => {
    const { socket } = await library();
    socket.receive({ type: 'query', id: 2, ref: 'ha:room', query: 'browse', args: { path: 5 } });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({
      id: 2,
      ok: true,
      data: { items: expect.any(Array) },
    });
  });

  test('an unknown integration, and one with no library, are errors', async () => {
    const { socket } = await setup();
    socket.receive({ type: 'query', id: 3, ref: 'zz:room', query: 'browse' });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 3, ok: false });
    socket.receive({ type: 'query', id: 4, ref: 'ha:lamp', query: 'browse' });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 4, ok: false });
  });
});

describe('history queries', () => {
  async function withHistory() {
    class WithHistory extends MockIntegration {
      readonly asked: unknown[] = [];

      override history(entityId: string, query: unknown) {
        this.asked.push([entityId, query]);
        return Promise.resolve({ kind: 'measurement' as const, points: [] });
      }
    }

    const ha = new WithHistory({ entities: { power: mockSensor({ value: '1' }) } });
    await ha.connect();
    const socket = new FakeSocket();
    new Proxy([ha]).handleConnection(socket);
    return { ha, socket };
  }

  test('a known range and bucket reach the integration, and nothing else does', async () => {
    const { ha, socket } = await withHistory();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:power',
      query: 'history',
      args: { range: '1w', bucket: '1h', evil: 'dropped' },
    });

    await settle();
    expect(ha.asked).toEqual([['power', { range: '1w', bucket: '1h' }]]);
    expect(socket.sent.at(-1)).toMatchObject({ id: 1, ok: true, data: { points: [] } });
  });

  test('a missing or unknown range, or an integration that keeps no history, is an error', async () => {
    const { ha, socket } = await withHistory();
    socket.receive({
      type: 'query',
      id: 2,
      ref: 'ha:power',
      query: 'history',
      args: { range: '5y' },
    });

    socket.receive({ type: 'query', id: 3, ref: 'ha:power', query: 'history' });
    await settle();
    expect(ha.asked).toEqual([]);
    expect(socket.sent.slice(-2)).toMatchObject([
      { id: 2, ok: false },
      { id: 3, ok: false },
    ]);

    const { ha: plainHa, socket: plain } = await setup();
    // The mock invents a history; this one keeps none.
    (plainHa as unknown as { history?: undefined }).history = undefined;
    plain.receive({
      type: 'query',
      id: 4,
      ref: 'ha:lamp',
      query: 'history',
      args: { range: '1d' },
    });

    await settle();
    expect(plain.sent.at(-1)).toMatchObject({ id: 4, ok: false });
  });
});

describe('logbook queries', () => {
  async function withLogbook() {
    class WithLogbook extends MockIntegration {
      readonly asked: unknown[] = [];

      override logbook(entityId: string, query: LogbookQuery) {
        this.asked.push([entityId, query]);
        return Promise.resolve({ entries: [] });
      }
    }

    const ha = new WithLogbook({ entities: { lamp: mockLight({ on: true }) } });
    await ha.connect();
    const socket = new FakeSocket();
    new Proxy([ha]).handleConnection(socket);
    return { ha, socket };
  }

  test('a limit is kept within bounds, and nothing else reaches the integration', async () => {
    const { ha, socket } = await withLogbook();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:lamp',
      query: 'logbook',
      args: { limit: 9999, evil: 'dropped' },
    });

    socket.receive({
      type: 'query',
      id: 2,
      ref: 'ha:lamp',
      query: 'logbook',
      args: { limit: 'x' },
    });

    await settle();
    expect(ha.asked).toEqual([
      ['lamp', { limit: 100 }],
      ['lamp', {}],
    ]);

    expect(socket.sent.at(-1)).toMatchObject({ id: 2, ok: true, data: { entries: [] } });
  });

  test('an integration that keeps no activity is an error', async () => {
    const { ha, socket } = await withLogbook();
    (ha as unknown as { logbook?: undefined }).logbook = undefined;
    socket.receive({ type: 'query', id: 3, ref: 'ha:lamp', query: 'logbook' });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 3, ok: false });
  });
});

describe('queue queries', () => {
  async function withQueue() {
    class WithQueue extends MockIntegration {
      readonly asked: unknown[] = [];

      override queue(entityId: string, query: QueueQuery) {
        this.asked.push([entityId, query]);
        return Promise.resolve({ items: [], total: 0, offset: 0 });
      }
    }

    const ha = new WithQueue({ entities: { room: mockMediaPlayer({}) } });
    await ha.connect();
    const socket = new FakeSocket();
    new Proxy([ha]).handleConnection(socket);
    return { ha, socket };
  }

  test('a limit is kept within bounds, and nothing else reaches the integration', async () => {
    const { ha, socket } = await withQueue();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:room',
      query: 'queue',
      args: { limit: 99999, evil: 'dropped' },
    });

    socket.receive({ type: 'query', id: 2, ref: 'ha:room', query: 'queue', args: { limit: 'x' } });
    await settle();
    expect(ha.asked).toEqual([
      ['room', { limit: 200 }],
      ['room', {}],
    ]);

    expect(socket.sent.at(-1)).toMatchObject({ id: 2, ok: true, data: { total: 0 } });
  });

  test('asking for the whole queue’s length reaches the integration only when it is exactly true', async () => {
    const { ha, socket } = await withQueue();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:room',
      query: 'queue',
      args: { duration: true },
    });

    socket.receive({
      type: 'query',
      id: 2,
      ref: 'ha:room',
      query: 'queue',
      args: { duration: 'yes' },
    });

    await settle();
    expect(ha.asked).toEqual([
      ['room', { duration: true }],
      ['room', {}],
    ]);
  });

  test('an integration with no queue is an error', async () => {
    const { ha, socket } = await withQueue();
    (ha as unknown as { queue?: undefined }).queue = undefined;
    socket.receive({ type: 'query', id: 3, ref: 'ha:room', query: 'queue' });
    await settle();
    expect(socket.sent.at(-1)).toMatchObject({ id: 3, ok: false });
  });
});

describe('forecast queries', () => {
  async function withForecast() {
    class WithForecast extends MockIntegration {
      readonly asked: unknown[] = [];

      override forecast(entityId: string, query: ForecastQuery) {
        this.asked.push([entityId, query]);
        return Promise.resolve({ type: query.type, points: [] });
      }
    }

    const ha = new WithForecast({ entities: { sky: mockWeather() } });
    await ha.connect();
    const socket = new FakeSocket();
    new Proxy([ha]).handleConnection(socket);
    return { ha, socket };
  }

  test('a known type reaches the integration, and nothing else does', async () => {
    const { ha, socket } = await withForecast();
    socket.receive({
      type: 'query',
      id: 1,
      ref: 'ha:sky',
      query: 'forecast',
      args: { type: 'hourly', evil: 'dropped' },
    });

    await settle();
    expect(ha.asked).toEqual([['sky', { type: 'hourly' }]]);
    expect(socket.sent.at(-1)).toMatchObject({ id: 1, ok: true, data: { type: 'hourly' } });
  });

  test('a missing or unknown type, or an integration without forecasts, is an error', async () => {
    const { ha, socket } = await withForecast();
    socket.receive({ type: 'query', id: 2, ref: 'ha:sky', query: 'forecast', args: { type: 'x' } });
    socket.receive({ type: 'query', id: 3, ref: 'ha:sky', query: 'forecast' });
    await settle();
    expect(ha.asked).toEqual([]);
    expect(socket.sent.slice(-2)).toMatchObject([
      { id: 2, ok: false },
      { id: 3, ok: false },
    ]);

    const { ha: plain, socket: plainSocket } = await withForecast();
    // The mock gives forecasts; this one does not.
    (plain as unknown as { forecast?: undefined }).forecast = undefined;
    plainSocket.receive({
      type: 'query',
      id: 4,
      ref: 'ha:sky',
      query: 'forecast',
      args: { type: 'daily' },
    });

    await settle();
    expect(plainSocket.sent.at(-1)).toMatchObject({ id: 4, ok: false });
  });
});

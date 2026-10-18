import type { EntityRef } from './entity.ts';
import type { ConnectionStatus, Unsubscribe } from './integration.ts';
import type {
  BrowseQuery,
  BrowseResult,
  Entity,
  ForecastQuery,
  ForecastResult,
  HistoryQuery,
  HistoryResult,
  LogbookQuery,
  LogbookResult,
  QueueQuery,
  QueueResult,
} from './model/index.ts';
import { encodeMessage, parseServerMessage, type ClientMessage } from './protocol.ts';

export type LinkStatus = 'connecting' | 'open' | 'closed';

export interface RemoteClientOptions {
  /** WebSocket URL of the runtime, e.g. `ws://localhost:3000/ws`. */
  url: string;

  /** Override for tests / non-browser environments. */
  createSocket?: (url: string) => WebSocket;
  reconnectMinMs?: number;
  reconnectMaxMs?: number;

  /** How often an open connection is asked whether it is alive, in ms. Default 5000. */
  heartbeatMs?: number;

  /** How long an open connection may be silent before it is taken to be dead and opened again, in ms. Default 15000, three missed
   * beats. Only once the runtime has answered a beat: an older runtime does not, and a quiet connection to it is not a dead one. */
  deadAfterMs?: number;
}

export type EntityListener = (entity: Entity | null | undefined) => void;

/**
 * What the UI layer needs from a backend connection. `RemoteClient` talks to the runtime over
 * WebSocket; `LocalClient` wraps integrations in-process (gallery, tests, demos).
 */
export interface Client {
  readonly link: LinkStatus;
  connect(): void;
  close(): void;
  onLinkChange(listener: (status: LinkStatus) => void): Unsubscribe;

  /** Status of a backend integration (`undefined` until known). */
  getIntegrationStatus(integration: string): ConnectionStatus | undefined;
  onIntegrationStatusChange(listener: () => void): Unsubscribe;

  /** Every known integration's status. The same object is returned until something changes, so
   * it is safe as a `useSyncExternalStore` snapshot. */
  getIntegrationStatuses(): Readonly<Record<string, ConnectionStatus>>;

  /** `undefined` while loading, `null` if the entity does not exist. */
  getEntity(ref: EntityRef): Entity | null | undefined;
  subscribe(ref: EntityRef, listener: EntityListener): Unsubscribe;

  /** Runs a named command on the entity; resolves once the backend accepted it. */
  command(ref: EntityRef, name: string, args?: Record<string, unknown>): Promise<void>;

  /** Lists one level of the entity's media library, or searches it. */
  browse(ref: EntityRef, query: BrowseQuery): Promise<BrowseResult>;

  /** The entity's past values, bucketed, from the backend's own record. */
  history(ref: EntityRef, query: HistoryQuery): Promise<HistoryResult>;

  /** A weather entity's forecast: days, hours or half-days ahead, soonest first. */
  forecast(ref: EntityRef, query: ForecastQuery): Promise<ForecastResult>;

  /** What happened to an entity lately and who or what caused it, newest first. */
  logbook(ref: EntityRef, query: LogbookQuery): Promise<LogbookResult>;

  /** A media player's queue, in the order it plays. */
  queue(ref: EntityRef, query: QueueQuery): Promise<QueueResult>;

  /** Escape hatch: a backend-specific request, answered with whatever the integration returns. */
  callRaw(integration: string, request: Record<string, unknown>): Promise<unknown>;
}

/**
 * Browser-side (and React-free) client for the runtime proxy. Ref-counts
 * subscriptions, resubscribes and reconnects with backoff. Listeners receive
 * `undefined` until the server has answered, then an entity or `null` (unknown entity).
 */
/** How long a request waits for the connection to open before it fails, in ms. */
const WAIT_MS = 10_000;

export class RemoteClient implements Client {
  #options: RemoteClientOptions;
  #socket: WebSocket | undefined;
  #link: LinkStatus = 'closed';
  #linkListeners = new Set<(status: LinkStatus) => void>();
  #integrationStatus = new Map<string, ConnectionStatus>();
  #statusSnapshot: Readonly<Record<string, ConnectionStatus>> = {};
  #statusListeners = new Set<() => void>();
  #entities = new Map<EntityRef, Entity | null>();
  #listeners = new Map<EntityRef, Set<EntityListener>>();
  #pending = new Map<
    number,
    { resolve: (data: unknown) => void; reject: (error: Error) => void }
  >();
  #nextId = 1;

  /** Requests made while the connection was not open yet (opening, or back after a drop): each is
   * sent when it opens, or given up on after a while. */
  #waiting = new Set<{
    open: () => void;
    fail: (error: Error) => void;
    timer: ReturnType<typeof setTimeout>;
  }>();
  #retry = 0;
  #timer: ReturnType<typeof setTimeout> | undefined;

  /** The beat of an open connection, when something was last heard on it, and whether the runtime answers beats at all. */
  #beat: ReturnType<typeof setInterval> | undefined;
  #heard = 0;
  #answers = false;
  #stopped = true;

  constructor(options: RemoteClientOptions) {
    this.#options = options;
  }

  get link(): LinkStatus {
    return this.#link;
  }

  connect(): void {
    if (!this.#stopped) {
      return;
    }

    this.#stopped = false;
    this.#open();
  }

  close(): void {
    this.#stopped = true;
    clearTimeout(this.#timer);
    clearInterval(this.#beat);
    this.#giveUpWaiting(new Error('Connection closed'));
    this.#socket?.close();
    this.#socket = undefined;
    this.#setLink('closed');
  }

  onLinkChange(listener: (status: LinkStatus) => void): Unsubscribe {
    this.#linkListeners.add(listener);
    return () => this.#linkListeners.delete(listener) && undefined;
  }

  /** Status of a backend integration as reported by the runtime (`undefined` until known). */
  getIntegrationStatus(integration: string): ConnectionStatus | undefined {
    return this.#integrationStatus.get(integration);
  }

  getIntegrationStatuses(): Readonly<Record<string, ConnectionStatus>> {
    return this.#statusSnapshot;
  }

  onIntegrationStatusChange(listener: () => void): Unsubscribe {
    this.#statusListeners.add(listener);
    return () => this.#statusListeners.delete(listener) && undefined;
  }

  getEntity(ref: EntityRef): Entity | null | undefined {
    return this.#entities.get(ref);
  }

  subscribe(ref: EntityRef, listener: EntityListener): Unsubscribe {
    let set = this.#listeners.get(ref);
    if (!set) {
      set = new Set();
      this.#listeners.set(ref, set);
      this.#send({ type: 'subscribe', ref });
    }

    set.add(listener);
    listener(this.#entities.get(ref));
    return () => {
      set.delete(listener);
      if (set.size === 0) {
        this.#listeners.delete(ref);
        this.#send({ type: 'unsubscribe', ref });
      }
    };
  }

  async command(ref: EntityRef, name: string, args?: Record<string, unknown>): Promise<void> {
    await this.#request((id) => ({
      type: 'command',
      id,
      ref,
      command: name,
      ...(args ? { args } : {}),
    }));
  }

  async browse(ref: EntityRef, query: BrowseQuery): Promise<BrowseResult> {
    return (await this.#request(
      (id) => ({
        type: 'query',
        id,
        ref,
        query: 'browse',
        args: { ...query },
      }),
      true,
    )) as BrowseResult;
  }

  async history(ref: EntityRef, query: HistoryQuery): Promise<HistoryResult> {
    return (await this.#request(
      (id) => ({
        type: 'query',
        id,
        ref,
        query: 'history',
        args: { ...query },
      }),
      true,
    )) as HistoryResult;
  }

  async forecast(ref: EntityRef, query: ForecastQuery): Promise<ForecastResult> {
    return (await this.#request(
      (id) => ({
        type: 'query',
        id,
        ref,
        query: 'forecast',
        args: { ...query },
      }),
      true,
    )) as ForecastResult;
  }

  async logbook(ref: EntityRef, query: LogbookQuery): Promise<LogbookResult> {
    return (await this.#request(
      (id) => ({
        type: 'query',
        id,
        ref,
        query: 'logbook',
        args: { ...query },
      }),
      true,
    )) as LogbookResult;
  }

  async queue(ref: EntityRef, query: QueueQuery): Promise<QueueResult> {
    return (await this.#request(
      (id) => ({
        type: 'query',
        id,
        ref,
        query: 'queue',
        args: { ...query },
      }),
      true,
    )) as QueueResult;
  }

  callRaw(integration: string, request: Record<string, unknown>): Promise<unknown> {
    return this.#request((id) => ({ type: 'raw', id, integration, request }));
  }

  /** Resolves once the connection is open: now if it is, else when it opens. The page asks for things
   * the moment it appears, which can be before the socket has finished opening, and a request made
   * then waits for it instead of failing. It fails if the connection does not come within
   * `WAIT_MS` (or the client was closed), and the caller can ask again later. */
  #whenOpen(): Promise<void> {
    if (this.#socket?.readyState === 1) {
      return Promise.resolve();
    }

    if (this.#stopped) {
      return Promise.reject(new Error('Not connected to runtime'));
    }

    return new Promise((resolve, reject) => {
      const waiter = {
        open: () => {
          clearTimeout(waiter.timer);
          resolve();
        },
        fail: (error: Error) => {
          clearTimeout(waiter.timer);
          reject(error);
        },
        timer: setTimeout(() => {
          this.#waiting.delete(waiter);
          reject(new Error('Not connected to runtime'));
        }, WAIT_MS),
      };

      this.#waiting.add(waiter);
    });
  }

  #giveUpWaiting(error: Error) {
    for (const waiter of Array.from(this.#waiting)) {
      waiter.fail(error);
    }

    this.#waiting.clear();
  }

  /** Sends a request and resolves with its answer. A read (`wait`) made before the connection is open
   * waits for it; a command or a raw request never does, since doing it late, after the person has
   * seen it fail, would be a surprise. */
  #request(build: (id: number) => ClientMessage, wait = false): Promise<unknown> {
    if (!wait || this.#socket?.readyState === 1) {
      return this.#sendRequest(build);
    }

    return this.#whenOpen().then(() => this.#sendRequest(build));
  }

  #sendRequest(build: (id: number) => ClientMessage): Promise<unknown> {
    const id = this.#nextId++;
    return new Promise((resolve, reject) => {
      if (this.#socket?.readyState !== 1) {
        reject(new Error('Not connected to runtime'));
        return;
      }

      this.#pending.set(id, { resolve, reject });
      this.#send(build(id));
    });
  }

  #setLink(status: LinkStatus) {
    if (status === this.#link) {
      return;
    }

    this.#link = status;
    for (const listener of Array.from(this.#linkListeners)) {
      listener(status);
    }
  }

  #send(message: ClientMessage) {
    if (this.#socket?.readyState === 1) {
      this.#socket.send(encodeMessage(message));
    }
  }

  #open() {
    this.#setLink('connecting');
    const socket = (this.#options.createSocket ?? ((url) => new WebSocket(url)))(this.#options.url);
    this.#socket = socket;

    socket.addEventListener('open', () => {
      this.#retry = 0;
      this.#setLink('open');
      for (const waiter of Array.from(this.#waiting)) {
        waiter.open();
      }

      this.#waiting.clear();
      for (const ref of this.#listeners.keys()) {
        this.#send({ type: 'subscribe', ref });
      }

      this.#startBeat(socket);
    });

    socket.addEventListener('message', (event) => {
      const message = parseServerMessage(String(event.data));
      if (message) {
        this.#heard = Date.now();
        this.#handle(message);
      }
    });

    socket.addEventListener('close', () => this.#lost(socket));
  }

  /** Asks the runtime now and then whether it is there, and gives the connection up when it has gone quiet: a socket whose other end
   * went away without closing (a laptop asleep, a network that dropped) says nothing for minutes, all of which the page would show
   * as connected. */
  #startBeat(socket: WebSocket) {
    clearInterval(this.#beat);
    this.#heard = Date.now();
    this.#answers = false;
    const every = this.#options.heartbeatMs ?? 5_000;
    const deadAfter = this.#options.deadAfterMs ?? 15_000;
    this.#beat = setInterval(() => {
      if (this.#socket !== socket) {
        return;
      }

      if (this.#answers && Date.now() - this.#heard > deadAfter) {
        this.#lost(socket);
        return;
      }

      this.#send({ type: 'ping' });
    }, every);
  }

  /** The connection is gone, closed or found dead: its requests fail and it is opened again after a pause. */
  #lost(socket: WebSocket) {
    if (this.#socket !== socket) {
      return;
    }

    clearInterval(this.#beat);
    this.#socket = undefined;
    try {
      socket.close();
    } catch {
      // Already closed, or never open: nothing to give up.
    }

    for (const { reject } of this.#pending.values()) {
      reject(new Error('Connection lost'));
    }

    this.#pending.clear();
    this.#setLink('closed');
    if (this.#stopped) {
      return;
    }

    const min = this.#options.reconnectMinMs ?? 500;
    const max = this.#options.reconnectMaxMs ?? 10_000;
    const delay = Math.min(max, min * 2 ** this.#retry++);
    this.#timer = setTimeout(() => this.#open(), delay);
  }

  #handle(message: ReturnType<typeof parseServerMessage> & object) {
    switch (message.type) {
      case 'pong':
        this.#answers = true;
        break;
      case 'entity':
        this.#entities.set(message.ref, message.entity);
        for (const listener of Array.from(this.#listeners.get(message.ref) ?? [])) {
          listener(message.entity);
        }

        break;
      case 'status':
        this.#integrationStatus.set(message.integration, message.status);
        this.#statusSnapshot = Object.fromEntries(this.#integrationStatus);
        for (const listener of Array.from(this.#statusListeners)) {
          listener();
        }

        break;
      case 'result': {
        const pending = this.#pending.get(message.id);
        if (!pending) {
          break;
        }

        this.#pending.delete(message.id);
        if (message.ok) {
          pending.resolve(message.data);
        } else {
          pending.reject(new Error(message.error));
        }

        break;
      }
    }
  }
}

import {
  encodeMessage,
  parseClientMessage,
  parseEntityRef,
  type BrowseQuery,
  type ConnectionStatus,
  type EntityRef,
  type ForecastQuery,
  type ForecastType,
  type LogbookQuery,
  type QueueQuery,
  type HistoryBucket,
  type HistoryQuery,
  type HistoryRange,
  type Integration,
  type ServerMessage,
  type Unsubscribe,
  UnknownEntityError,
} from '@hashsome/core';

/** The subset of a `ws` WebSocket the proxy needs. */
export interface ProxySocket {
  send(data: string): void;
  on(event: 'message', listener: (data: { toString(): string }) => void): unknown;
  on(event: 'close' | 'error', listener: () => void): unknown;
  readonly readyState: number;
}

const OPEN = 1;

const RANGES = ['1h', '1d', '1w', '1m'];
const BUCKETS = ['5m', '1h', '1d'];

/** A history query's fields, kept only when they are ones it has: the rest came from a browser. A
 * missing or unknown range is a request that cannot be answered. */
function historyArgs(args: Record<string, unknown> | undefined): HistoryQuery | undefined {
  const { range, bucket } = args ?? {};
  if (typeof range !== 'string' || !RANGES.includes(range)) {
    return undefined;
  }

  return {
    range: range as HistoryRange,
    ...(typeof bucket === 'string' && BUCKETS.includes(bucket)
      ? { bucket: bucket as HistoryBucket }
      : {}),
  };
}

const FORECAST_TYPES = ['daily', 'hourly', 'twice_daily'];

/** A forecast query's one field, kept only when it is a kind there is: the rest came from a browser. */
function forecastArgs(args: Record<string, unknown> | undefined): ForecastQuery | undefined {
  const { type } = args ?? {};
  return typeof type === 'string' && FORECAST_TYPES.includes(type)
    ? { type: type as ForecastType }
    : undefined;
}

/** A logbook query's one field, kept only when it is a sensible number: the rest came from a browser. */
function logbookArgs(args: Record<string, unknown> | undefined): LogbookQuery {
  const { limit } = args ?? {};
  return typeof limit === 'number' && Number.isFinite(limit)
    ? { limit: Math.max(1, Math.min(Math.floor(limit), 100)) }
    : {};
}

/** A queue query's fields, kept only when they are what they should be: the rest came from a browser. */
function queueArgs(args: Record<string, unknown> | undefined): QueueQuery {
  const { limit, duration } = args ?? {};
  return {
    ...(typeof limit === 'number' && Number.isFinite(limit)
      ? { limit: Math.max(1, Math.min(Math.floor(limit), 200)) }
      : {}),
    ...(duration === true ? { duration: true } : {}),
  };
}

/** The only fields a browse query has, kept only when they are strings: the rest came from a browser. */
function browseArgs(args: Record<string, unknown> | undefined): BrowseQuery {
  return {
    ...(typeof args?.path === 'string' ? { path: args.path } : {}),
    ...(typeof args?.search === 'string' ? { search: args.search } : {}),
  };
}

/** Answers one read (`browse`, `history` or `forecast`) from the integration that owns the entity. */
function runQuery(
  integration: Integration,
  integrationId: string,
  id: string,
  query: 'browse' | 'history' | 'forecast' | 'logbook' | 'queue',
  args: Record<string, unknown> | undefined,
): Promise<unknown> {
  switch (query) {
    case 'queue':
      return integration.queue
        ? integration.queue(id, queueArgs(args))
        : Promise.reject(new Error(`"${integrationId}" has no queue`));

    case 'logbook':
      return integration.logbook
        ? integration.logbook(id, logbookArgs(args))
        : Promise.reject(new Error(`"${integrationId}" keeps no activity`));

    case 'forecast': {
      const forecast = forecastArgs(args);
      if (!integration.forecast) {
        return Promise.reject(new Error(`"${integrationId}" has no forecasts`));
      }

      return forecast
        ? integration.forecast(id, forecast)
        : Promise.reject(new Error('A forecast query needs a type'));
    }

    case 'history': {
      const history = historyArgs(args);
      if (!integration.history) {
        return Promise.reject(new Error(`"${integrationId}" keeps no history`));
      }

      return history
        ? integration.history(id, history)
        : Promise.reject(new Error('A history query needs a range'));
    }

    default:
      return integration.browse
        ? integration.browse(id, browseArgs(args))
        : Promise.reject(new Error(`"${integrationId}" has no media library`));
  }
}

/**
 * Multiplexes browser connections onto server-held integrations. Tokens never
 * reach the browser; current states are replayed from the integrations' stores
 * so a waking tablet gets a full picture immediately.
 */
export interface ProxyOptions {
  /** Where problems worth a line in the server log go (an unknown ref, a failed subscribe). */
  log?: (message: string) => void;
}

/**
 * Multiplexes browser connections onto server-held integrations. Tokens never
 * reach the browser; current entities are replayed from the integrations' stores
 * so a waking tablet gets a full picture immediately.
 *
 * The proxy does all the addressing: it parses `<integration>:<id>` and hands the integration only
 * its local id. A subscription is resolved once the owning integration is connected — until then
 * the browser has no answer (a component shows its loading state) — and an id the integration does
 * not have is answered with `entity: null` and logged.
 */
export class Proxy {
  #integrations = new Map<string, Integration>();
  #log: (message: string) => void;

  constructor(integrations: Integration[], options: ProxyOptions = {}) {
    for (const integration of integrations) {
      this.#integrations.set(integration.id, integration);
    }

    this.#log = options.log ?? (() => {});
  }

  handleConnection(socket: ProxySocket): void {
    const send = (message: ServerMessage) => {
      if (socket.readyState === OPEN) {
        socket.send(encodeMessage(message));
      }
    };

    const subscriptions = new Map<EntityRef, Unsubscribe>();
    const cleanups: Unsubscribe[] = [];

    for (const integration of this.#integrations.values()) {
      const report = (status: ConnectionStatus) =>
        send({ type: 'status', integration: integration.id, status });

      report(integration.status);
      cleanups.push(integration.onStatusChange(report));
    }

    const answer = (id: number, outcome: Promise<unknown>) => {
      outcome.then(
        (data) => send({ type: 'result', id, ok: true, ...(data !== undefined ? { data } : {}) }),
        (error: unknown) =>
          send({
            type: 'result',
            id,
            ok: false,
            error: error instanceof Error ? error.message : String(error),
          }),
      );
    };

    const subscribe = (ref: EntityRef): Unsubscribe => {
      const { integration: integrationId, id } = parseEntityRef(ref);
      const integration = this.#integrations.get(integrationId);
      if (!integration) {
        this.#log(`[proxy] no integration "${integrationId}" for ${ref}`);
        send({ type: 'entity', ref, entity: null });
        return () => {};
      }

      const start = (): Unsubscribe => {
        try {
          return integration.subscribe(id, (entity) =>
            send({ type: 'entity', ref, entity: entity ?? null }),
          );
        } catch (error) {
          if (!(error instanceof UnknownEntityError)) {
            throw error;
          }

          this.#log(`[proxy] ${error.message}`);
          send({ type: 'entity', ref, entity: null });
          return () => {};
        }
      };

      if (integration.status === 'connected') {
        return start();
      }

      let stop: Unsubscribe | undefined;
      const off = integration.onStatusChange((status) => {
        if (status === 'connected' && !stop) {
          stop = start();
        }
      });

      return () => {
        off();
        stop?.();
      };
    };

    socket.on('message', (data) => {
      const message = parseClientMessage(data.toString());
      if (!message) {
        return;
      }

      switch (message.type) {
        case 'ping':
          send({ type: 'pong' });
          return;
        case 'subscribe':
          if (!subscriptions.has(message.ref)) {
            subscriptions.set(message.ref, subscribe(message.ref));
          }

          return;
        case 'unsubscribe':
          subscriptions.get(message.ref)?.();
          subscriptions.delete(message.ref);
          return;
        case 'command': {
          const { integration: integrationId, id } = parseEntityRef(message.ref);
          const integration = this.#integrations.get(integrationId);
          answer(
            message.id,
            integration
              ? integration.command(id, message.command, message.args)
              : Promise.reject(new Error(`Unknown integration "${integrationId}"`)),
          );

          return;
        }

        case 'query': {
          const { integration: integrationId, id } = parseEntityRef(message.ref);
          const integration = this.#integrations.get(integrationId);
          answer(
            message.id,
            integration
              ? runQuery(integration, integrationId, id, message.query, message.args)
              : Promise.reject(new Error(`Unknown integration "${integrationId}"`)),
          );

          return;
        }

        case 'raw': {
          const integration = this.#integrations.get(message.integration);
          answer(
            message.id,
            !integration
              ? Promise.reject(new Error(`Unknown integration "${message.integration}"`))
              : integration.callRaw
                ? integration.callRaw(message.request)
                : Promise.reject(new Error(`"${message.integration}" has no raw requests`)),
          );

          return;
        }
      }
    });

    const dispose = () => {
      for (const off of subscriptions.values()) {
        off();
      }

      subscriptions.clear();
      for (const off of cleanups) {
        off();
      }

      cleanups.length = 0;
    };

    socket.on('close', dispose);
    socket.on('error', dispose);
  }
}

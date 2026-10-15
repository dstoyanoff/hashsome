import type { EntityRef } from './entity.ts';
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

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';

export type Unsubscribe = () => void;

/**
 * A backend the runtime can talk to (Home Assistant, Music Assistant, ...). This is the whole
 * surface a third-party integration package implements — see ARCHITECTURE.md, "The integration
 * contract", for the rules each member must follow.
 *
 * Integrations work only in **local ids** (the part of a ref after `<integration>:`); the runtime
 * does all the addressing, and `BaseIntegration` stamps `ref` onto the entities it stores.
 */
/** What a browser asks of a file besides which one it is. */
export interface AssetOptions {
  /** About how many pixels wide it will be drawn: a backend that can resize gives the nearest size it has that is not smaller, one that cannot gives the file as it is. */
  size?: number;
}

export interface Integration {
  /** The prefix of every ref this integration owns. Fixed for the instance's lifetime. */
  readonly id: string;

  readonly status: ConnectionStatus;

  /** Resolves once connected **and** the first full set of entities is loaded; rejects if the
   * first attempt fails; idempotent. After a first success the integration reconnects itself. */
  connect(): Promise<void>;

  /** Synchronous and idempotent: closes the link, cancels timers, rejects in-flight commands. */
  disconnect(): void;

  /** Called only when the status actually changes. */
  onStatusChange(listener: (status: ConnectionStatus) => void): Unsubscribe;

  listEntities(): Entity[];
  getEntity(entityId: string): Entity | undefined;

  /**
   * Calls `listener` immediately with the current entity, then on every change (`undefined` if
   * the entity is removed). Throws `UnknownEntityError` for an id this integration does not have.
   */
  subscribe(entityId: string, listener: (entity: Entity | undefined) => void): Unsubscribe;

  /**
   * Runs a named command from the entity's kind (`setVolume`, `next`). Resolves once the backend
   * has accepted it, not when the state changes. Rejects with a displayable `Error` for an
   * unknown entity, a command the kind does not define, invalid arguments, or a refusing backend.
   * Arguments come from a browser: validate them.
   */
  command(entityId: string, name: string, args?: Record<string, unknown>): Promise<void>;

  /**
   * Lists one level of the entity's media library (or searches it). Only for players that report
   * `capabilities.browse`; the library is the integration's own, with no mixing across backends.
   * Rejects with a displayable `Error` for an unknown entity or path. Arguments come from a browser.
   */
  browse?(entityId: string, query: BrowseQuery): Promise<BrowseResult>;

  /**
   * The entity's past values, from the backend's own record (Home Assistant's long-term
   * statistics), bucketed. Optional: only for backends that keep history. Resolves with no points
   * for an entity with none, and rejects with a displayable `Error` for an unknown entity.
   * Arguments come from a browser: validate them.
   */
  history?(entityId: string, query: HistoryQuery): Promise<HistoryResult>;

  /**
   * A weather entity's forecast of the asked kind (days, hours, half-days), soonest first. Optional:
   * only for backends that have forecasts. Resolves with no points when the entity cannot give that
   * kind, and rejects with a displayable `Error` for an unknown entity. Arguments come from a
   * browser: validate them.
   */
  forecast?(entityId: string, query: ForecastQuery): Promise<ForecastResult>;

  /**
   * Recent activity of an entity: what happened to it (turned on, went offline) and who or what
   * caused it, newest first. Optional: only for backends that keep such a record (Home Assistant's
   * logbook). Resolves with no entries for an entity with none, and rejects with a displayable
   * `Error` for an unknown entity. Arguments come from a browser: validate them.
   */
  logbook?(entityId: string, query: LogbookQuery): Promise<LogbookResult>;

  /**
   * A media player's queue: the tracks that play, in order, starting a little before the current one.
   * Optional: only for players that report `capabilities.queue`. Resolves with no items for a player
   * with an empty queue, and rejects with a displayable `Error` for an unknown entity. Arguments come
   * from a browser: validate them.
   */
  queue?(entityId: string, query: QueueQuery): Promise<QueueResult>;

  /**
   * Fetches a file the backend serves for its entities, such as artwork, with the integration's own
   * credentials. `path` is the backend's own path and starts with `/`; it comes from a browser, so
   * refuse anything else. The runtime serves only image responses. Optional: omit it when entities
   * only carry addresses the browser can open itself.
   */
  fetchAsset?(path: string, options?: AssetOptions): Promise<Response>;

  /**
   * Whether a path `fetchAsset` takes always gives the same file (an address made from the file's own identity, as
   * Music Assistant's `/imageproxy/<id>` is), so a browser may keep it for good. Optional: without it, or for a path it
   * says no to, a file is kept for an hour, since the same address can give another one later.
   */
  isStableAsset?(path: string): boolean;

  /** Escape hatch: a backend-specific request no command covers. `@hashsome/ui` never calls it. */
  callRaw?(request: Record<string, unknown>): Promise<unknown>;
}

export type { EntityRef };

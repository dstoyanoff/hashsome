# Architecture

How Hashsome is layered, and the contract an integration implements. Read this before adding a
component, a device kind, or an integration.

> **Status.** This describes the **target** design. Parts of it are not built yet; each section
> says what exists **today** and what is **planned**, and the table at the end tracks migration.
> When code and this document disagree about the target, fix the code or change this document —
> don't leave them apart.

## Principles

1. **The UI knows nothing about integrations.** `@hashsome/ui` components care about state and
   interactions only. They never name Home Assistant, Music Assistant, a service, or an
   integration-specific attribute.
2. **Integrations translate.** Each integration maps its backend's shape to a generic entity
   model, and maps generic commands back to its backend. The translation lives in the integration
   package, runs on the server, and is the only place that knows the backend.
3. **Using a component stays one prop.** Cards take an entity id; the generic object is resolved
   for them. The object form exists for custom sources, not as the default.
4. **Integrations are ordinary npm packages.** Anyone can publish one by implementing the contract
   in `@hashsome/core`. Nothing in `@hashsome/core`, `@hashsome/ui` or `@hashsome/runtime` special-cases an
   integration.
5. **A project is an app, not a configuration.** Dashboards are ordinary React code that composes
   `@hashsome/ui` components. The framework provides components, a hosting runtime and integrations.

## Layers

```
 backend            integration (npm package)        @hashsome/core                @hashsome/ui
 ───────            ─────────────────────────        ──────────                ────────
 Home Assistant ──► maps its entities to the   ──►  generic entity model ──►  hook(ref) → object → component
 Music Assistant    generic model; maps             named commands
 anything else      generic commands back           wire protocol
                    (server only)                   integration contract
                                    ▲                        ▲
                                    └──── @hashsome/runtime ─────┘
                                     hosts integrations, proxies them to the browser
```

| Package               | Owns                                                                                           | Must not                                                       |
| --------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `@hashsome/core`      | Entity refs, the generic model, commands, the integration contract, the wire protocol, clients | Depend on React, or name any backend                           |
| integration packages  | Connecting to one backend; translating state and commands                                      | Be imported by browser code; depend on `@hashsome/ui`          |
| `@hashsome/runtime`   | The CLI, hosting integrations, the browser proxy, route helpers                                | Know about any specific integration or render any UI chrome    |
| `@hashsome/ui`        | Design system, hooks, components                                                               | Name an integration, a backend service, or a backend attribute |
| a project (`example`) | `hashsome.config.ts` (which integrations), its dashboards, its shared files                    | —                                                              |

## Entity refs

An entity is addressed by `<integration>:<id>`, for example `ha:light.kitchen` or
`ma:living_room`.

- `<integration>` is the `id` of the integration that owns the entity. Ids are unique per project;
  the runtime refuses to start if two integrations claim the same one.
- `<id>` is opaque to everything except the owning integration. It may contain dots or colons.
  Nothing outside the integration may parse it (no "the part before the dot is the domain").
- A ref is an address, not a coupling: `ha:` says who answers for the entity, not what shape it has.

**Status:** built (`packages/core/src/entity.ts`).

## The generic entity model

Every entity an integration exposes is one object of a known **kind**.

```ts
interface EntityBase<K extends string = string> {
  /** `<integration>:<id>`. */
  ref: EntityRef;
  /** What this entity is; selects the model and the commands. */
  kind: K;
  /** Human-readable name, already resolved by the integration. */
  name: string;
  /** `ready`: state is meaningful. `unavailable`: the device is unreachable.
   *  `unknown`: reachable but it has not reported yet. */
  availability: 'ready' | 'unavailable' | 'unknown';
  /** ISO 8601. When the state last changed / was last reported. */
  lastChanged?: string;
  lastUpdated?: string;
  /** The backend's own payload, untouched. An escape hatch for project code; see the rules. */
  raw?: Record<string, unknown>;
}
```

"Loading" and "does not exist" are not availabilities: the browser sees `undefined` while it waits
for the first answer and `null` when the integration has no such entity.

### Kinds

Each kind is one file in `@hashsome/core` holding its state, its **capabilities** and its **commands**.
The first kind, in full:

```ts
interface MediaPlayerEntity extends EntityBase<'mediaPlayer'> {
  playback: 'playing' | 'paused' | 'idle' | 'off' | 'buffering';
  media?: { title?: string; artist?: string; album?: string; artworkUrl?: string };
  /** Seconds into the current item as of `positionUpdatedAt` (ISO); a UI advances it itself while playing. */
  position?: number;
  duration?: number;
  positionUpdatedAt?: string;
  /** Plays its queue in a random order. Absent when the player has no shuffle. */
  shuffle?: boolean;
  /** 0..1. Absent when the player has no volume control. */
  volume?: number;
  muted: boolean;
  /** The group it plays in with others, the same on every one (`leader` is the player the stream belongs to,
   * itself on the leader). Absent while it plays alone. A follower shows what its leader plays. */
  group?: { leader: EntityRef; members: EntityRef[] };
  /** The players it can be grouped with: what to offer to add to its stream. */
  groupable?: EntityRef[];
  capabilities: {
    volume: boolean;
    mute: boolean;
    next: boolean;
    previous: boolean;
    /** Has a browsable library (see `Integration.browse`). */
    browse: boolean;
    /** The library can be searched. */
    search: boolean;
    /** Playback can be moved to a position. */
    seek: boolean;
    /** Shuffle can be turned on and off. */
    shuffle: boolean;
    /** Playback can be moved to another player. */
    transfer: boolean;
    /** Can be grouped with other players (`setGroupMembers`, `leaveGroup`, `takeOverGroup`); `groupable` says with which. */
    group: boolean;
  };
}

interface MediaPlayerCommands {
  play: void;
  pause: void;
  togglePlay: void;
  next: void;
  previous: void;
  setVolume: { volume: number }; // 0..1
  setMuted: { muted: boolean };
  seek: { position: number }; // seconds
  setShuffle: { shuffle: boolean };
  /** Plays a `BrowseItem` of this player's own library; `mode` queues it instead (`next`, `add`). */
  playMedia: { item: string; mode?: 'play' | 'replace' | 'next' | 'add' };
  /** Adds players to this player's stream and takes players out of it, this player being its leader.
   * Joining someone else's stream is sent to that leader (`add: [me]`); a follower cannot lead. */
  setGroupMembers: { add?: EntityRef[]; remove?: EntityRef[] };
  /** Takes this player out of its group; a leader leaving ends the group. */
  leaveGroup: void;
  /** A follower takes the stream over: the player that led it leaves (and stops), and the others follow this one. */
  takeOverGroup: void;
}
```

Planned kinds, finalized when each is migrated: `light` (on, brightness 0..1, color temperature in
kelvin with its range, color as hue/saturation; capabilities for each), `climate` (mode from a
closed set, target and current temperature, step and range, presets), `sensor` (numeric or text
value, unit, measurement class), `action` (a one-shot thing to trigger: scene, script, button).
Anything else is `kind: 'generic'` with a text `value`.

### Model rules

- **Normalized units.** Fractions are 0..1 (brightness, volume). Color temperature is kelvin.
  Temperatures carry their unit. Never a backend's native scale (0–255, 0–100).
- **Capabilities, not mode lists.** A component asks "can this light do color temperature?" by
  reading `capabilities`, never by interpreting a backend's mode strings.
- **Closed vocabularies.** Enumerations (`playback`, climate modes) are fixed sets defined in
  `@hashsome/core`. An integration maps its values onto them; a value with no equivalent maps to the
  nearest one, and the original stays in `raw`.
- **Nothing backend-named.** No field or command name comes from a backend's API.
- **`raw` is for projects, not for `@hashsome/ui`.** A dashboard may read `raw` for something the model
  does not cover. A `@hashsome/ui` component must not. If a component needs a field, the field belongs
  in the model.
- **`raw` reaches the browser.** It must never contain credentials or anything not meant for the
  person looking at the dashboard.
- **Additive evolution.** New fields and capabilities are optional. Removing or changing one is a
  breaking change to `@hashsome/core`.

### Custom kinds

`kind` is an open string, so a third-party integration can ship its own kind together with the
component that renders it, without changing `@hashsome/core`. Unprefixed kind names are reserved for
`@hashsome/core`; a third-party kind uses a prefix (`acme.blind`).

**Status:** built (`packages/core/src/model/`): `mediaPlayer`, `light`, `climate`, `sensor`, `switch`,
`action`, `person` and `generic`.

## The integration contract

An integration is a class that implements `Integration`. This is the whole surface a third-party
package has to provide.

```ts
type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error';
type Unsubscribe = () => void;

interface Integration {
  // ── identity ────────────────────────────────────────────────────────────
  readonly id: string;

  // ── connection ──────────────────────────────────────────────────────────
  readonly status: ConnectionStatus;
  connect(): Promise<void>;
  disconnect(): void;
  onStatusChange(listener: (status: ConnectionStatus) => void): Unsubscribe;

  // ── entities ────────────────────────────────────────────────────────────
  listEntities(): Entity[];
  getEntity(entityId: string): Entity | undefined;
  subscribe(entityId: string, listener: (entity: Entity | undefined) => void): Unsubscribe;

  // ── commands ────────────────────────────────────────────────────────────
  command(entityId: string, name: string, args?: Record<string, unknown>): Promise<void>;

  // ── optional capabilities ───────────────────────────────────────────────
  history?(entityId: string, query: HistoryQuery): Promise<HistoryResult>; // bucketed past values
  forecast?(entityId: string, query: ForecastQuery): Promise<ForecastResult>; // a weather entity's days / hours ahead
  fetchAsset?(path: string): Promise<Response>; // artwork etc., fetched with the integration's credentials
  logbook?(entityId: string, query: LogbookQuery): Promise<LogbookResult>; // what happened to it, and who or what caused it
  browse?(entityId: string, query: { path?: string; search?: string }): Promise<BrowseResult>;

  // ── escape hatch ────────────────────────────────────────────────────────
  callRaw?(request: Record<string, unknown>): Promise<unknown>;
}
```

**Integrations never see a ref.** Every `entityId` an integration receives is its own local id.
`@hashsome/core` and the runtime do the addressing in one place: they parse `<integration>:<id>`, pick
the integration by its `id`, and pass on only the local id. In the other direction the integration
hands over entities keyed by local id, and `BaseIntegration` stamps `ref` onto them. An integration
neither strips nor builds prefixes.

### Identity

- **`id`** — the prefix of every ref the integration owns. It has a short default (`ha`, `ma`) and
  must be overridable through the integration's options, so a project can run two instances of the
  same integration (`ha` and `ha-cabin`). It is fixed for the lifetime of the instance.

### Connection

- **`connect()`** resolves once the integration is authenticated **and** has loaded its first full
  set of entities, so `listEntities()` is complete when it resolves. It rejects if that first
  attempt fails. It is idempotent: calling it while connected or connecting does not open a second
  connection.
- **Who retries.** The runtime calls `connect()` once at startup and, if it rejects, retries with
  exponential backoff (1s up to 30s) forever; a backend being down never stops the server. After
  the first success the **integration** owns reconnection: when the link drops it reconnects by
  itself with backoff, and the runtime does not call `connect()` again.
- **`disconnect()`** is synchronous and idempotent. It closes the link, cancels reconnect timers,
  rejects in-flight commands, and sets the status to `disconnected`. The instance may be
  `connect()`ed again afterwards.
- **`status`** and **`onStatusChange`**:

  | Status         | Meaning                                                                                                                       |
  | -------------- | ----------------------------------------------------------------------------------------------------------------------------- |
  | `disconnected` | Not connected and not trying (initial state, or after `disconnect()`, or a dropped link before the reconnect attempt starts). |
  | `connecting`   | A connection attempt is in progress.                                                                                          |
  | `connected`    | Authenticated; entities are live.                                                                                             |
  | `error`        | The last attempt failed (bad token, unreachable host).                                                                        |

  Listeners are called only when the status actually changes. The current value is read from
  `status`, not delivered on subscribe. The browser shows this status per integration.

- **While not connected**, the integration keeps its last known entities and marks them
  `availability: 'unavailable'`, so a dashboard dims instead of going blank.

### Entities

- **`listEntities()`** returns every entity currently known. It is used for discovery (finding
  entity ids, generating typed refs) and for choosers (a speaker selector). It is synchronous and
  reads the integration's in-memory store.
- **`getEntity(id)`** returns the current entity, or `undefined` if the integration has no entity
  with that id.
- **`subscribe(id, listener)`**:
  - **throws `UnknownEntityError`** if the integration has no entity with that id. Subscribing to
    something that does not exist is a mistake (a typo, a removed device), and it is reported as
    one instead of waiting silently for the entity to appear;
  - otherwise calls `listener` **immediately and synchronously** with the current entity;
  - calls it again on every change, and with `undefined` if the entity is later removed;
  - returns an `Unsubscribe` that is safe to call more than once.

  "Does not exist" can only be decided once the integration knows its entities, so the runtime
  subscribes only after `connect()` has resolved (see the wire protocol). An integration is never
  asked about an id before it has its first full set. A consequence: a device added to the backend
  later is picked up by new subscriptions (a page that loads afterwards), not by one that already
  failed.

- **Identity and immutability.** An emitted entity object is never mutated afterwards. A new object
  is emitted only when something changed; an unchanged entity keeps its object identity across
  backend refreshes. React rendering relies on this.
- **Translation happens here.** The entity an integration stores is already the generic model.
  Mapping is a pure function from the backend's payload to an entity (everything but `ref`, which
  the base class adds), kept in its own module per kind so it can be unit-tested without a
  connection.

### Commands

- **`command(id, name, args)`** performs a named command from the entity's kind (`setVolume`,
  `next`) and resolves once the backend has **accepted** it. It does not wait for the resulting
  state change; that arrives through `subscribe`.
- It rejects with an `Error` whose message can be shown to a person: unknown entity, a command the
  kind does not define, a command this entity's capabilities do not allow, invalid arguments, not
  connected, or the backend refusing. The message crosses to the browser, so it must not contain
  secrets.
- **Arguments are untrusted.** They originate in a browser. The integration validates them (types,
  ranges) before touching the backend.
- **Normalized in, native out.** `setVolume { volume: 0.4 }` arrives as a fraction; the integration
  converts it to whatever the backend wants.
- No optimistic state. The integration reports what the backend reports. Components that want an
  instant response keep their own pending value until the real state catches up.

### Optional capabilities

An integration implements only what its backend can do. A missing method means "not supported" and
the UI hides the feature.

- **`history(id, { from, to, resolution? })`** → timestamped numeric samples, oldest first. Feeds
  charts and min / max / usage totals.
- **`forecast(id, { type })`** → a weather entity's forecast, soonest first: `daily`, `hourly` or
  `twice_daily`. The weather entity lists the types its source supports in `forecasts`; Home Assistant
  answers from `weather.get_forecasts`, whichever provider sits behind the entity. Feeds the top bar's
  weather chip (today's high and low) and its forecast drawer.
- **`logbook(id, { limit? })`** → recent activity entries (what happened, who or what caused it, when),
  newest first. Home Assistant answers from the logbook (`logbook/get_events`, the last week), writing
  the message from the state it changed to, and names a person from the `person` entity that carries
  the Home Assistant user id. Feeds the History section of the light and climate drawers.
- **`browse(id, { path?, search? })`** → one level of a player's media library, or a search of it, as
  `BrowseItem`s (`id`, `title`, `subtitle?`, `artworkUrl?`, `kind`, `playable`, `expandable`). The
  item `id` is opaque: it goes back to `browse` as `path` to open it, or to `playMedia` to play it.
  **One source per player:** the integration that owns the player is the media source (a `ma:`
  player lists Music Assistant's library, an `ha:` player Home Assistant's), never a mix. Music
  Assistant maps shelves (recently played, playlists, albums, artists, radio) onto its library
  commands and supports search; Home Assistant passes `media_player/browse_media` through, whose
  content depends on the player, and has no search. Artwork is passed on only when the browser can
  open it without a login; a runtime image proxy for the rest is planned.
  A top level that is nothing but folders to open (two or more) is a set of shelves, and the UI shows
  it as tabs that open on the first shelf instead of a list; any other top level is a plain list.

Each of these should also be advertised per entity through a capability flag where a kind has one
(`MediaPlayerEntity.capabilities.browse`), so a component can decide without calling.

### Escape hatch

- **`callRaw(request)`** passes a backend-specific request through (a Home Assistant service call
  that no command covers). It exists so a project is never blocked by the model. `@hashsome/ui` never
  calls it. If something is needed often, it becomes a command.

### `BaseIntegration`

`@hashsome/core` ships an abstract class that implements the bookkeeping, so an integration only writes
the backend-specific parts.

```ts
abstract class BaseIntegration implements Integration {
  abstract readonly id: string;
  abstract connect(): Promise<void>;
  abstract disconnect(): void;
  abstract command(entityId: string, name: string, args?: Record<string, unknown>): Promise<void>;

  // provided
  get status(): ConnectionStatus;
  onStatusChange(listener): Unsubscribe;
  listEntities(): Entity[];
  getEntity(entityId: string): Entity | undefined;
  subscribe(entityId: string, listener): Unsubscribe;

  // for subclasses
  protected setStatus(status: ConnectionStatus): void;
  protected setEntity(entityId: string, entity: EntityInput | undefined): void;
  protected replaceEntities(next: Map<string, EntityInput>): void;
}

/** An entity as an integration produces it: the model without `ref`. */
type EntityInput = Omit<Entity, 'ref'>;
```

- `setStatus` notifies listeners only on a real change.
- `setEntity` stores (or removes, with `undefined`) one entity, stamps its `ref` from the
  integration's `id` and the local id, and notifies its subscribers.
- `subscribe` throws `UnknownEntityError` for an id that is not in the store.
- `replaceEntities` swaps the whole store and notifies only for ids whose object identity changed,
  which is why an integration must reuse the previous object for an unchanged entity.

A subclass therefore has three jobs: manage the connection and call `setStatus`; map backend
updates to entities and call `setEntity` / `replaceEntities`; translate `command`.

### Packaging an integration

- **A normal npm package**, ESM, with `@hashsome/core` as a `peerDependency`. Suggested name:
  `hash-integration-<backend>` (or scoped).
- **Server only.** It is constructed in `hashsome.config.ts`, which the runtime loads in Node. It is
  never bundled for the browser, so it may use Node APIs and hold credentials. It must not import
  `@hashsome/ui` or React.
- **Exports** the integration class and its options type. Options include the connection details,
  an optional `id`, and a seam to replace the transport in tests (a `createClient` or
  `createSocket` function).
- **A mock, at `./mock`.** The package's `exports` has a `./mock` entry whose `createMock(options?)`
  returns a `MockIntegration` (from `@hashsome/core`) with the integration's own default `id` and a
  representative set of devices: every kind it can produce, and the states a dashboard has to
  handle, including an unavailable device. Build the fixtures from the backend's raw payloads and
  run them through the integration's real mapper, so the mock cannot drift from it. This lets a
  project, a test or the gallery try any device without a backend.
- **Registration** is the only wiring:

  ```ts
  // hashsome.config.ts
  import { defineConfig } from '@hashsome/runtime';
  import { AcmeIntegration } from 'hashsome-integration-acme';

  export default defineConfig({
    integrations: [
      new AcmeIntegration({ url: process.env.ACME_URL!, token: process.env.ACME_TOKEN! }),
    ],
  });
  ```

- **Credentials** stay in the integration. They are read from the project's environment and never
  appear in an entity, an error message, or `raw`.

### What a conforming integration guarantees

A checklist for authors. Items 10 and 11 are enforced today (see below); the rest are not yet.

1. `id` is stable and overridable. The integration works only in local ids and never builds or
   parses a ref.
2. `connect()` resolves only after the first full entity set is loaded; rejects on first-attempt
   failure; is idempotent.
3. After a first success it reconnects by itself; while disconnected, entities stay listed and are
   `unavailable`.
4. `disconnect()` is synchronous, idempotent, and leaves no timers or sockets.
5. `subscribe` throws for an unknown id; otherwise it delivers the current value synchronously,
   then every change, then `undefined` on removal.
6. Unchanged entities keep their object identity; emitted objects are never mutated.
7. Every entity is a valid model object for its `kind`, with normalized units and truthful
   capabilities.
8. `command` validates its arguments, rejects with a displayable message, and never reports state
   the backend did not report.
9. No credentials leave the integration.
10. The package exports a mock at `./mock` (`createMock`) that covers every kind it can produce.
11. The package runs the shared conformance suite against that mock.

**Enforced.** `@hashsome/core/conformance` exports `runIntegrationConformance({ name, defaultId, kinds,
createMock })`, which an integration calls from a test in `src/__tests__/`. It checks the mock's
default id, that it connects and covers every declared kind, that every entity is valid and
addressed by this integration, that there is an unavailable device, `subscribe` semantics, that
every command of every kind runs and bad commands reject, and that no credentials appear.
`packages/integrations/mock-contract.test.ts` fails for any integration package that lacks the
`./mock` export or does not call the suite, so a new integration cannot skip either.

**Status:** built. `Integration`, `BaseIntegration`, the mock integration and `UnknownEntityError` live in
`packages/core/src`; the runtime strips the prefix and defers subscriptions until an integration is
connected. `browse`, `history`, `forecast` and `logbook` are built (below). The mock conformance suite is (below).

## Wire protocol

The browser never talks to a backend. It talks to the runtime over one WebSocket (`/ws`), and the
runtime talks to the integrations.

Browser → runtime:

| Message                                        | Meaning                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------- |
| `{ type: 'subscribe', ref }` / `'unsubscribe'` | Start / stop receiving an entity                                    |
| `{ type: 'command', id, ref, command, args? }` | Run a command; answered by `result`                                 |
| `{ type: 'query', id, ref, query, args? }`     | `history` / `forecast` / `logbook` / `browse`; answered by `result` |
| `{ type: 'raw', id, integration, request }`    | The escape hatch                                                    |

Runtime → browser:

| Message                                                            | Meaning                                        |
| ------------------------------------------------------------------ | ---------------------------------------------- |
| `{ type: 'entity', ref, entity }`                                  | Current entity; `null` means it does not exist |
| `{ type: 'status', integration, status }`                          | An integration's connection status             |
| `{ type: 'result', id, ok: true, data? }` / `{ ok: false, error }` | Outcome of a command, query or raw request     |

Every browser message is validated before it reaches an integration, and the runtime does all the
addressing: it parses the ref, finds the integration by its prefix and passes on the local id.

A subscription is resolved once the owning integration is connected. Until then the browser has no
answer and the component shows its loading state. Then:

- the entity exists: the runtime sends it, and every later change;
- the integration does not have it (`UnknownEntityError`), or no integration owns the prefix: the
  runtime sends `entity: null` and logs the ref. The component shows "Not found".

When a browser connects, the runtime replays each integration's status and the current value of
every entity it subscribes to, so a display that wakes up gets a full picture at once.

**Status:** built for `subscribe`, `unsubscribe`, `command`, `raw`, `entity`, `status` and `result`;
`query` carries `browse`, `history`, `forecast` and `logbook`.

## The UI

Three layers, each with one job:

```
useEntity(ref)            the generic entity, live            Entity | null | undefined
usePlayer(ref)            entity + its commands bound         Player (state + methods)
<MediaPlayerBar … />      renders a Player                    no knowledge of where it came from
```

- **Hooks** resolve a ref. A kind hook (`usePlayer`, `useLight`, `useClimate`) returns one object:
  the entity's state plus methods that send its commands (`player.next()`, `light.setBrightness(0.6)`).
- **Components take an id or an object.**

  ```tsx
  <MediaPlayerBar entity="ma:living_room" />   // the normal form: one prop
  <MediaPlayerBar player={myPlayer} />         // a custom source, a test, the gallery
  ```

  The id form is a thin wrapper that calls the hook and renders the object form. The object form is
  a pure view: no provider entity, no network.

- **Lifecycle is uniform.** Every component handles loading, not found, unavailable and unknown the
  same way. Dashboards never check availability themselves.
- **Rules for `@hashsome/ui`:** no integration names, no backend service or attribute names, no reads
  of `raw`, no `callRaw`. A guard test will enforce the first three for migrated kinds.

### Styling

No runtime styling library: each component has a CSS Module (`kebab-case.module.css`) beside it, and the browser
does the rest. The theme is CSS custom properties: `HashsomeProvider` writes the palette, radii, shadows, layers,
type scale and the density's sizes as `--hs-*` on `:root` (`style/variables.ts`, one `<style id="hashsome-theme">`),
so a light/dark switch, a density or a project's `overrides` is that one block of text being replaced, not
components drawn again. `style/base.css` (in `@layer hs-base`, so nothing of a component's or a project's loses to
it) is the page's colors, font, reset and scrollbars; `style/type.module.css` (kept in sync by hand with
`TYPOGRAPHY_KEYS`) is one class per variant of the type scale, taken with `composes:`. A value only known at
run time (a measured size, a color an app chose) is an inline custom property the CSS reads. JavaScript that needs a
number (the grid arithmetic) reads `useDensity()`, and a chart's SVG `useColor()`, from `ThemeContext`. Icons are not the UI's: an `icon` prop takes any element (`icon={<Lightbulb />}` from `lucide-react`, `<IconBath />` from `@tabler/icons-react`, or a project's own SVG), so the bundler tree-shakes the library and a project is free to pick another. `Icon` sizes and draws the element the way the app does (the density's icon size, a thin outline); an icon with parts to color (the weather's) styles them by shape order. The bodies of drawers and overlays (library, queue, forecast, history) are `lazyExport`s, fetched when first opened. A published
`dist` carries the `.css` files next to the scripts (`scripts/copy-css.ts`), so a consumer's bundler handles them.

**Status:** built. `useEntity`, `useCommand` and `useEntityHandle` are in `packages/ui/src/hooks.ts`;
cards take `EntityRef | EntityHandle<K>` (`entity-handle.ts`). Kind hooks such as `usePlayer` were
replaced by the handle.

## Repository layout

```
packages/
  core/                      @hashsome/core             entity model, integration contract, wire protocol
  integrations/
    home-assistant/          @hashsome/integration.home-assistant
    music-assistant/         @hashsome/integration.music-assistant
  ui/                        @hashsome/ui               design system, hooks, entity components, the gallery
  runtime/                   @hashsome/runtime          the hashsome CLI and the server it hosts
example/                     @hashsome/example          a complete project: the reference for your own
  hashsome.config.ts               which integrations, and how they are configured
  app/                         a React Router app: root.tsx, routes.ts (every route in one file)
  shared/                      conventions the dashboards reuse (top bar, the switcher's list)
  dashboards/<id>/             one folder per dashboard: layout.tsx, pages/*.tsx
demo/                        @hashsome/demo             the example's dashboards as a static site on mock data (GitHub Pages)
android/                     dev.hashsome:*             the Kotlin libraries that render dashboards natively (its own Gradle build, see "Native Android")
  client/ headless/ ui/ kiosk/ testing/                 one Gradle module each; parity.json and PARITY.md say what mirrors @hashsome/ui
example/android/             a consumer's Android app, built against ../../android with includeBuild
docs/screenshots/            the images the README shows
```

`packages/*` is the framework and knows nothing about a particular home. `example/` is an ordinary
project that uses it, the same shape as a real user's own repository; this repository hosts both so
the framework's tests and the dogfood dashboards live together. Dashboards are plain folders under a
project, not packages, and the repository's tests cover `packages/*` only.

## How a project is wired

- **One routing file.** A dashboard is reachable because `app/routes.ts` has a `route('<id>', …)`
  for it, at `/<id>` from the root. Nothing is discovered: there is no index of installed
  dashboards, and no dashboard manifest. The tab title is the entry module's `meta` export.
- **A dashboard owns its layout.** It renders the top bar, a navigation rail or dock itself, or
  leaves them out. A multi-page dashboard is a layout route with child routes, and its navigation
  takes an absolute `base` with items relative to it.
- **Conventions are the project's.** `shared/` holds what several dashboards reuse (one top bar,
  the dashboard switcher's list). `@hash` does not wire any of it; it is ordinary code.
- **The dashboard code is ordinary app code**, not a sandbox: it can import any library, the
  project's own files, or its own helpers. `@hashsome/ui` components are the building blocks, and
  `@hashsome/ui` is styled with a CSS Module per component, written in the theme's custom properties.
- **Integrations are configured in one place.** `hashsome.config.ts` constructs them, usually from
  environment variables. The server reads this file at startup only, so a change needs a restart.

## Agent workflow

The repository is set up so a coding agent can add a dashboard without touching the framework.
The skills in `.claude/skills/` hold what an agent needs, and they read well for a person too:

| Skill                      | What it is for                                                                   |
| -------------------------- | -------------------------------------------------------------------------------- |
| `create-dashboard`         | The end-to-end process for a new dashboard                                       |
| `dashboard-rules`          | The hard constraints, and the sharp edges found while building the real examples |
| `entity-discovery`         | Finding real entity ids instead of guessing them                                 |
| `ui-catalog`               | Every component and its props, generated from source so it cannot go stale       |
| `verify-dashboard`         | How to check the result actually works before calling it done                    |
| `add-ui-component`         | The stricter, separate workflow for extending the design system itself           |
| `create-android-dashboard` | The end-to-end process for a dashboard on the native Android side                |
| `android-component`        | Adding a component to `android/ui`: headless control, view, parity entry, tests  |

The main rule is that creating a dashboard does not edit `packages/*`. A real gap in `@hashsome/ui` is
a separate, explicit change (see `add-ui-component`), so "make me a dashboard" can never turn into
"also changed the framework". `CATALOG.md` is generated (`pnpm generate:catalog`) and CI fails if
it drifts from the source. The same holds on Android: a dashboard does not edit `android/*`.

## Native Android

`android/` renders the same dashboards natively on Android, for the wall displays where a WebView is
too slow. It is a second client of the runtime the web dashboards use (`/ws` and
`/_hashsome/asset`): the Node server, `@hashsome/core` and the integrations are unchanged, and the
entity model and wire protocol are the contract between the two sides. Dashboards are written in
Kotlin with a DSL, in the consumer's own repository, the way a web project holds `dashboards/`.

**Layers.** Five Gradle modules (group `dev.hashsome`, one AAR each), and dependencies point down only:

| Module     | Holds                                                                                                        | Depends on |
| ---------- | ------------------------------------------------------------------------------------------------------------ | ---------- |
| `client`   | `Store`, `Scheduler`, `Transport`/`WsClient`, `Client`, the typed entity models and parsers, `Images`        | nothing    |
| `headless` | `Scope`, `EntityHandle`, one state holder per control (`LightControl`, ...), the theme schedule              | `client`   |
| `ui`       | theme and density, icons, base views, layouts and the page DSL, components, `DashboardActivity`              | `headless` |
| `kiosk`    | device profiles, screen, presence, night mode, foreground service, MQTT bridge to Home Assistant, `Settings` | `ui`       |
| `testing`  | `TestScheduler`, `FakeTransport`, `FakeClient`, JSON fixtures generated from `@hashsome/core`                | `client`   |

The direction is enforced at build time, not by convention: each module's `check` runs
`checkDependencies` (`android/build.gradle.kts`), which fails when a module declares anything but the
one module under it, the Kotlin stdlib (and the MQTT client, for `kiosk` alone); `headless` also
fails if it imports `android.*`, so it keeps running on a plain JVM; lint runs with `NewApi` fatal.
`./gradlew build` runs all of it, and CI and the release run that.

**Headless first.** Each control is a state holder with no View in it (what the web has as a hook);
the view binds to it. That is also what makes the logic testable on the JVM with `FakeClient` and
`TestScheduler`. A component is added in that order (see `android-component`).

**Must-nots.** No AndroidX, no coroutines, no kotlinx.serialization in any library: state is
`Store<T>` read on the main thread, JSON is `org.json`. Only API 24-safe platform calls (the
libraries support minSdk 24, and the JDK surface is that of API 24: no `java.time`). No reflection;
an icon is a resource id. A component reads every color, radius and size from the resolved theme
when it draws. No vendor-specific code in `client` and `headless`: what an entity is comes from the
model, not from which integration served it.

**Plug points.** `ui` never references `kiosk`. It exposes `ScreenPolicy`, `TouchListener` and
`PageListener` on `HashsomeApp` (defaults do nothing) and `kiosk` implements them; the consumer
installs it with `HashsomeApp.withKiosk(...)`. Other customization is data: `ThemeOverrides`, the
`font`, `Motion` per dashboard, `KioskConfig`.

**Protocol rules.** The client sends and understands what `@hashsome/core`'s wire protocol defines
and nothing else: subscribe/unsubscribe by `EntityRef`, commands by kind, queries, results with
ok/err, the heartbeat that finds a silent connection dead, resubscribe after a reconnect with
backoff. A kind the client does not know parses to `GenericEntity` with the raw payload kept; parsers
read tolerantly (`opt*`). Fixtures are generated from `@hashsome/core`'s mock builders
(`pnpm generate:fixtures`), so the Kotlin parsers are tested against what the server really sends,
and a change to the protocol breaks the Android tests, not a display.

**A consumer project.** Beside the web side of a project (`hashsome.config.ts`, `app/`,
`dashboards/`), the Android side is `android/` (a Gradle app depending on `dev.hashsome:*`, its
dashboards as Kotlin objects, a generated icon pack from `hashsome android icons`) and
`devices.json` at the root (one entry per display: `id`, `host`, `profile`, and optionally
`dashboard`, `server`, `mqtt`, `home`). `hashsome android build|deploy|provision|uninstall` drive
Gradle and `adb` from it (`--dry-run` prints the commands). `example/android` is the reference;
it uses `includeBuild("../../android")` so it builds against this checkout, as a consumer does with
a local checkout, while a consumer that wants the released libraries declares the GitHub Packages
repository and pins `hashsome` in its `gradle/libs.versions.toml`.

**Parity with the web UI.** `android/parity.json` has an entry for every export of `@hashsome/ui`
(`native`, `native:<Name>`, `planned` or `planned#<issue>`, `web-only`), plus `native-only` entries
with a reason. `pnpm generate:parity` writes `android/PARITY.md` from it and `pnpm check:parity`
fails CI for an export without an entry or an entry without an export. The rule: a change that adds
or renames a `packages/ui` export adds its entry in the same PR, and `native` means the Kotlin
component exists in that PR; otherwise it is `planned`.

**Device quirks live in `kiosk`.** Keeping the app in front on a display whose stock app stays HOME,
owning the backlight, waking on presence, removing the app's own device admin: a non-test device
admin cannot be removed with `dpm remove-active-admin`, and `pm uninstall` fails while it is active,
so `hashsome android uninstall` first broadcasts `dev.hashsome.REMOVE_ADMIN`, which the app answers
by removing its own admin.

## Packaging and deployment

`hashsome package <target…>` turns a project into something deployable. It is built where you
build it and never where it runs.

1. **Build the client.** `hashsome build` produces the static client in `build/client`. (The shared
   Vite config is a function so every load of it gets its own React Router plugin; the SPA build's
   preview step loads the config a second time in the same process.)
2. **Bundle the server.** Vite's own server build bundles the runtime, the project's
   `hashsome.config.ts` and every integration it imports, with their dependencies, into one
   `server.mjs`. It runs with plain Node 24 and no `node_modules`. The server needs its own file
   because it holds the tokens, proxies `/ws`, and serves backend artwork through `/_hashsome/asset/*`;
   the static client alone is not enough.
3. **Write the targets.** Each target is written under `./release` from that bundle:

   | Target    | Contents                                                                         |
   | --------- | -------------------------------------------------------------------------------- |
   | `plain`   | `server.mjs`, `client/`, a `Dockerfile`, a README                                |
   | `compose` | `compose.yaml` (reads `.env`), the project's `.env.example`, a README            |
   | `helm`    | a chart: Deployment (one replica, `Recreate`), Service, optional Ingress, probes |
   | `image`   | only `image.tar`                                                                 |

   `compose`, `helm` and `image` run a container image: a slim Node image with the bundle copied in
   (`COPY`, no install, no compile), run as the non-root `node` user with a `/healthz` health check.
   It is built for `--platform`, and saved as `image.tar` with a tag per release (the time, unless
   `--tag`), so a node always picks up an update.

**Secrets.** The bundle and the image hold none. The config reads its tokens and addresses from the
environment when the server runs: an `.env` for compose, a Kubernetes Secret for helm. The chart
takes the Secret's name (`existingSecret`) and never contains its values. A private certificate
authority for a backend is a mounted file named by `NODE_EXTRA_CA_CERTS`, since Node does not use
the system store by default.

**Loading the image onto k3s.** There is no registry. `k3s ctr -n k8s.io images import image.tar`
puts the image into a node's containerd, and the Deployment uses `imagePullPolicy: IfNotPresent`
(or `Never`) with that tag. On a cluster of several nodes the image must be present on every node a
pod can land on, or the pod pinned to one. k3s's embedded registry mirror (`embedded-registry`) can
share images between nodes in principle, but it needs the flag on servers and agents, and in
practice did not share an imported image on the cluster this was developed against. Importing on
each node is the dependable path.

**The Android app is not part of `hashsome package`.** The server side is unchanged: the same
bundle and image serve the web dashboards and the native app's `/ws` and `/_hashsome/asset`. The APK
is built by the consumer (`hashsome android build`, signed with the project's own keystore, so that
`adb install -r` never meets a different signature) and deployed over `adb` to each display
(`hashsome android deploy|provision`). Its address is the `server` of a device in `devices.json`.

**Hosting notes.** A pod on `hostNetwork` needs the `Recreate` strategy, at the Deployment's `spec`
level and not in the pod spec, or a rollout deadlocks on the node's port. The Service port in a
backend's URL is the Service's, not the container's.

## Adding things

**A kind.** Add its model and command map to `@hashsome/core`; map it in every integration that has
such devices; teach the mock integration its commands; add the kind hook and the component (id or
object) to `@hashsome/ui`; add it to the gallery with its props documented; update the status table.

**An integration.** Implement `Integration` (extend `BaseIntegration`); write one pure mapping
module per kind it supports, with unit tests; translate commands; add a transport seam and test
against a fake; export a `./mock` with `createMock()` and run `runIntegrationConformance` on it; register it in a project's `hashsome.config.ts`. No change to `@hashsome/core`,
`@hashsome/runtime` or `@hashsome/ui` is needed.

## Migration status

Every card kind is on the generic model. HA-shaped `EntityState` and `callService` are gone from
`@hashsome/core` and `@hashsome/ui`; Home Assistant's own shape lives only in its integration package.

| Piece                                                                                    | Status                 |
| ---------------------------------------------------------------------------------------- | ---------------------- |
| Generic model in `@hashsome/core`                                                        | done                   |
| `command` / `raw` on the contract and the wire                                           | done                   |
| Media player (Music Assistant native, Home Assistant mapped)                             | done                   |
| Light, climate, sensor, switch, action / scene, person                                   | done                   |
| `history` (energy totals, sensor history) and `logbook` (activity, from Home Assistant)  | done                   |
| `browse` / `search` / `playMedia` / `seek` (Music Assistant, Home Assistant, UI browser) | done                   |
| Queue ("up next", Music Assistant only), image proxy                                     | done                   |
| Speaker grouping: model, commands and mock (Music Assistant and the UI follow)           | in progress            |
| Speaker transfer                                                                         | not started            |
| Integration mocks and the mock conformance suite                                         | done                   |
| Typed refs per kind                                                                      | not started (optional) |

## Decisions

- **Versioning.** `@hashsome/core` follows semver, and its major version is the version of the model and
  of the integration contract. An integration tracks it: an integration at `2.x.x` works with
  `@hashsome/core` `2.x`. It declares `@hashsome/core` as a peer dependency on that major (`^2.0.0`), so a
  mismatch is reported at install time. Minor releases of core only add (optional fields, new
  kinds, new optional methods); anything that would break an integration is a major.
- **Rooms and grouping stay in the dashboard.** The model has no areas or rooms, and nothing builds
  a dashboard automatically: a backend exposes far more than anyone wants on a screen. A dashboard
  lists the entities it shows. Discovery tooling (the `entity-discovery` skill) may read a
  backend's own room assignment to _suggest_ a sensible layout, through integration-specific
  helpers that are not part of this contract.
- **Conformance suite.** `@hashsome/core/conformance` is a shared test helper that an integration runs
  against its own mock. It covers the mock and the model-level checks; the connection-level items
  (reconnect, idempotent `connect`, timers) need a transport fake per integration and are not
  asserted yet.

## Upgrading a project

`hashsome upgrade` (run through a project's `hashsome:upgrade` script: `upgrade` alone would be taken
by the package manager's own command) reads the project's `package.json`, finds its `@hashsome/*`
dependencies, skips any linked from a checkout (`workspace:`, `link:`, `file:`), and runs `pnpm update --latest`
on the rest. pnpm is the one supported manager: a project that names or has the lockfile of another gets
the command to run by hand. All packages share one version, so they move together. It is `packages/runtime/src/cli/upgrade.ts`.

A project with an Android app (`android/gradle/libs.versions.toml`) has that file's `hashsome = "<version>"` set to
the installed `@hashsome/runtime` version too, since the Maven libraries carry the same version as the npm
packages; `--dry-run` reports it without writing. A project that builds against a local checkout
(`includeBuild`) ignores the number, as the npm side ignores a `link:` dependency.

## Releasing

Packages are published to npm, and the Android libraries to GitHub Packages, by hand, from **Actions →
Release → Run workflow** on `main`.

- **What is published.** `@hashsome/core`, `ui`, `runtime` and the two integrations, all at one shared
  version (0.x while alpha), and `dev.hashsome:client|headless|ui|kiosk|testing` (AARs with sources)
  at that same version, to `https://maven.pkg.github.com/dstoyanoff/hashsome`. The Maven version
  lives in `android/gradle.properties` (`hashsome.version`), which `scripts/release.ts` rewrites with
  the package.json files (`VERSION_FILES`); never edit it by hand. A consumer needs `gpr.user` and
  `gpr.key` (a classic personal access token with `read:packages`) in `~/.gradle/gradle.properties`,
  since GitHub Packages asks for credentials even for a public repository. The repo keeps `exports` pointing at `src/*.ts` so development needs no
  build; each package's `publishConfig.exports` swaps them for the compiled `dist/` (ES modules plus
  `.d.ts`, no CommonJS) when `pnpm pack` makes the tarball. `pnpm build:packages` builds them.
- **The version** comes from the conventional commits since the last `v*` tag
  (`scripts/release.ts`): `feat` is a minor, `fix`/`perf` a patch, nothing else releases. Below 1.0 a
  breaking change is also a minor. The workflow's `level` input overrides it, and 1.0.0 is a manual
  `major`. Tick **dry run** to see the version and notes without publishing.
- **What a run does.** Checks (lint, typecheck, test, and `./gradlew build` in `android/`), writes the
  version into every package, `android/gradle.properties` and `CHANGELOG.md`, commits and tags
  `vX.Y.Z` locally, publishes every tarball to npm with provenance, then the Android libraries to
  GitHub Packages (after npm, since it is the likelier to fail and then recoverable by hand), and only
  then pushes the commit and tag to `main` and creates the GitHub release with the notes. A failed
  publish therefore leaves `main` untouched. GitHub Packages does not overwrite a published version,
  so a re-run after a partial Maven publish needs the modules already there skipped. Pushing to `main` uses the `RELEASE_TOKEN` secret (a
  fine-grained token with Contents write), since `main` requires PRs and the built-in token cannot
  bypass that.
- **The docs site follows the release.** After a run has published, its last job (`docs`) runs
  `pages.yml` for the tag it just made, so the component gallery and the live demo show what is
  released, not what is merged. A dry run, or a run with nothing to release, deploys nothing. It runs
  from `main`, the one ref the `github-pages` environment accepts. To redeploy by hand, run
  **Actions → Docs site** from `main` and name a tag (empty is the latest release).
- **npm auth** is trusted publishing (OIDC), configured once per package on npmjs.com for this
  repository and `release.yml`; there is no npm token in GitHub.

## Open questions

- **Typed refs per kind.** Optional. Generating types so `<LightTile entity>` only accepts refs of
  kind `light`. Refs are already typed as "known ids" when a project generates them; narrowing by
  kind is a convenience to weigh against the extra codegen.

---
name: dashboard-rules
description: The hard constraints for creating or editing a dashboard in example/dashboards/*. Load this before writing any dashboard code — it defines what you may touch and the sharp edges found while building example/dashboards/home.
---

# Dashboard rules

These are constraints, not suggestions. If a task seems to require breaking one, stop and say so
instead of working around it — see "When a rule is in the way" at the end.

## Scope

- A dashboard lives entirely under `example/dashboards/<id>/`. You may create and edit files
  there freely. It's a plain folder, not its own package — no `package.json`/`tsconfig.json`/
  `vitest.config.ts` of its own; it's built as part of the one `@hashsome/example` project.
  **Don't write tests for it** — this repo's test suite covers `packages/*` only; verify a
  dashboard with `verify-dashboard` instead (typecheck + an actual look at it running).
- **Never edit `packages/core`, `packages/ui`, or `packages/runtime`** while creating or changing a
  dashboard, even for a one-line fix, even if it seems trivial. If the design system is missing
  something, that's a separate, explicit task — see `add-ui-component`.
- A dashboard's code (`example/dashboards/<id>/**`) is ordinary app code and may import
  whatever it needs, including the project's own `example/shared/` files. Build with `@hashsome/ui`
  components and hooks; prefer them to raw `fetch`/`WebSocket`, which would bypass the runtime's
  entity handling. Don't import from `packages/` by relative path.
- `pnpm format` (write mode) reformats the _whole repo_, not just your dashboard — if another
  file happened to already be out of sync with oxfmt, running it can sweep in an unrelated
  change. After running it, check `git status`/`git diff` and make sure everything outside
  `example/dashboards/<id>/` (and the expected `hashsome.config.ts`/registration changes) is
  unchanged before committing.
- Read `.claude/skills/ui-catalog/CATALOG.md` before writing dashboard code. It lists every
  component, its props and its doc comment, generated straight from `packages/ui/src` so it can't
  be stale. Don't invent a component that isn't there — use what's listed, or raise it as a
  separate `add-ui-component` task.

## Required shape

Every dashboard is a plain folder at `example/dashboards/<id>/`:

```
example/dashboards/<id>/
├─ layout.tsx       optional: shared chrome (top bar, nav) around the pages; exports `meta`
└─ pages/*.tsx      route modules (default-export a component)
```

- `<id>` is kebab-case (`^[a-z0-9][a-z0-9-]*$`) and matches its URL: `route('<id>', …)` in
  `example/app/routes.ts`, the one file that lists every route. There is no manifest: the tab
  title is the entry module's `meta` export, and the switcher's list lives in `shared/dashboards.ts`.
- Use `create-dashboard` to start this from an existing dashboard — including the
  project-level registration step, not just the `dashboards/<id>/` folder. Don't hand-roll it.
- Layouts are responsive; there is no per-dashboard viewport. Ask what device it targets only to
  choose density and how much fits on screen.

## Layout and chrome

A dashboard owns its whole layout. There is no runtime-rendered chrome: a page builds its own structure from `@hashsome/ui` components:

- There is no page wrapper to write. The theme, spacing and density are global (`HashsomeProvider`;
  spacing follows density, so `gap={3}` is always one space on any display),
  and the padded, scrolling page every dashboard renders into is `@hashsome/ui`'s `Page`, rendered by
  the app's root layout (`example/app/root.tsx`) — the project's own code, free to swap for
  its own wrapper. A page just returns its rooms (see `dashboards/kitchen`).
- The **top bar** and the **nav** (`NavRail`, which is fixed to the left, `NavDock`, which floats at the bottom, or `NavBar`, the rail's design along the bottom) are ordinary
  components a dashboard includes — or leaves out. `NavRail`/`NavDock`/`NavBar` reserve their size in the surrounding
  `Page`, which pads so content never sits under them. A small kiosk panel is simply
  its content with no chrome (see `dashboards/hello`).
- A **multi-page** dashboard puts the shared structure in a layout route: `routes.ts` nests the pages under
  `route('<id>', 'layout.tsx', [index(…), route('lights', …)])`, and `layout.tsx` renders a
  `NavRail` (absolute `base`, e.g. `/home`, items relative to it), the top bar and an
  `<Outlet />` (see `dashboards/home`).
- A **wall display** that is left alone on a sub-page (a music page, say) can go back to the dashboard's main page by
  itself: `HashsomeProvider`'s `idleReturn` (a time in ms, off by default) turns it on for every `NavRail`/`NavDock`/`NavBar`,
  and one can set its own `idleReturn` (a time, or `false`) over it. The main page is the nav item with `to: ''`.
- To make several dashboards match, share the configuration in the project: `example/shared/`
  holds `HomeTopBar` (weather, presence, clock, the switcher) and `dashboards.ts` (which dashboards
  the switcher lists). That is a **convention of the example project** — `@hash` knows nothing about
  it, and a dashboard can ignore it or use different chrome.
- Dashboards are ordinary app code: they may import anything, including the project's own shared
  files. The only things to avoid are dashboard-specific CSS and hand-rolled entity state handling
  (see Style).

## Entity refs and state

- Refs are `<integration>:<id>` strings, `<integration>` being whichever integration's `id` claims
  that prefix (enforced unique at startup — see `entity-discovery`'s "A third integration"). The
  local id's own shape is owned by that integration, not a fixed rule: `ha:<domain>.<name>` for
  Home Assistant (e.g. `ha:light.kitchen_lamp`), `ma:<player_id>` for a `media_player`/
  `MediaPlayerBar` talked to directly via Music Assistant instead of through Home Assistant — the
  id is the player's own `player_id` verbatim, not a `domain.name` pair. Use `entity-discovery` to
  find real ids instead of guessing them.
- Every `@hashsome/ui` entity component already handles loading / not-found / unavailable / unknown
  states consistently (dims, disables, shows why). **Don't build your own state handling** —
  don't check `state === 'unavailable'` yourself or hide a tile when its entity is missing; pass
  the ref to the component and let it show the real state. A tile for a device that doesn't exist
  yet (not deployed, wrong id) should render as "Not found", not disappear or throw.

## Layout

Read `example/dashboards/home/pages/home.tsx` and
`example/dashboards/second-floor/pages/home.tsx` as the reference layout (two separate
dashboards, each a single page — see "Layout and chrome" above), and
run `pnpm docs:dev` to see
every component live, documented with its supported states. Concretely:

- Group by room: a `<RoomHeader title="..." icon={...} readouts={...} />` followed by that room's
  tiles (in a `<Grid columns={n}>`, or loose for a full-width row). The header adds its own space
  above, so consecutive rooms read as groups. Put per-room sensors in `readouts`, not as
  standalone tiles, unless they're the main point of that room.
- **Give a `ClimateTile` its own row.** It renders a mode button and a temperature stepper and
  needs real width — cramming it into the same 3-column grid as light tiles truncates its label.
  Found the hard way while building `example/dashboards/home`:
  ```tsx
  <RoomHeader title="living room" icon={<Sofa />} />
  <Grid columns={3}>
    <LightTile entity="ha:light.lamp" name="lamp" />
    {/* ...other lights */}
  </Grid>
  <ClimateTile entity="ha:climate.heater" name="heater" /> {/* full width, own row */}
  <Grid columns={2}>{/* scene/action buttons */}</Grid>
  ```
  (A room is just a header plus whatever rows follow it, so you can mix row shapes like this.)
- **Give every tile on a page a distinct label.** Two tiles both named "lamp" (e.g. a living-room
  lamp and a porch lamp) are ambiguous to a user, and to assistive tech reading the accessible
  name — found and fixed in `example/dashboards/home`. Prefer the entity's own room-qualified
  name, or pass an explicit `name` when it would otherwise collide.
- A media bar or anything else that wants the full width goes in its own row (no `Grid`), not
  squeezed into a grid cell.
- **A page for one known device can be a `Board`** (optional; flex columns keep working). It is `columns`
  equal columns (12 by default) and rows as tall as their content; each `<Cell cols={8} rows={1}>` says
  only how big it is and the board places the cells in order, in the first spot they fit. A room (its
  header and tiles) is one cell; a player beside three rooms is `<Cell cols={4} rows={3}>` and ends where
  the third row does; `rows="fill"` is a column from where it is placed down to the bottom of the page, whatever rows are beside it (they keep their place). The top row of a page
  that is not a `TopBar` is `<TopRow>` (the date, weather, clock and status, at the right). Never type a
  height for a row yourself: the grid's sizes are whole modules (the spacing unit, 4px), cards are
  already that tall, and the debug menu's "Show grid" (`HASHSOME_DEBUG=1`) draws the grid and flags a card that is off it.
  ```tsx
  <Board>
    <Cell>
      <TopRow>{/* DateChip, WeatherChip, Clock, SystemStatus */}</TopRow>
    </Cell>
    <Cell cols={8}>{/* RoomHeader + Grid of tiles */}</Cell>
    <Cell cols={4} rows="fill">
      <MediaPlayerColumn entity="ma:kitchen" overlays />
    </Cell>
  </Board>
  ```

## Multi-page dashboards

Put the shared structure in a layout route (see "Layout and chrome"): `routes.ts` nests the pages under
`route('<id>', '../dashboards/<id>/layout.tsx', [index(…), route('lights', …)])` and `layout.tsx` renders a `NavRail`
or `NavDock`, the top bar, and an `<Outlet />`. `NavRail`/`NavDock`/`NavBar` take an
absolute `base` (e.g. `/home`) and `items[].to` relative to it (`''` for the dashboard's
home, `'lights'` for `/<id>/lights`), and highlight the current page themselves. Read
`dashboards/home/layout.tsx` and its block in `app/routes.ts` for the working example.

## Icons

Icon props (`icon`) take an element from an icon library the project depends on: `icon={<Lightbulb />}`
(`import { Lightbulb } from 'lucide-react'`) or `icon={<IconVacuumCleaner />}` (from
`@tabler/icons-react`). Browse names at lucide.dev/icons and tabler.io/icons; a typo'd name fails
`pnpm typecheck`.

## Fonts

Inter is bundled. Another font is added to the project (`pnpm add @fontsource/<name>`, import its 400/500/600/700 CSS in `app/root.tsx`, name the family in `FONT`), never loaded from a CDN: the displays may have no internet. See the README's "Fonts".

## Style

- No custom CSS beyond what `@hashsome/ui` components already provide. If a layout genuinely can't be
  built from `Grid` / `RoomHeader` / `Tile` plus the entity components, that
  points at a missing primitive — raise it (see `add-ui-component`), don't reach for inline styles
  as a workaround.
- To change how everything looks (a color, a radius, a type size, the spacing), set `OVERRIDES` in
  `example/app/root.tsx` (`HashsomeProvider`'s `overrides`, partial, per light/dark for colors) —
  never edit `packages/ui` for that.
- For structural layout the `@hashsome/ui` primitives don't cover, a dashboard writes a small CSS Module
  of its own (`page.module.css` beside the page) in the theme's custom properties (`var(--hs-surface)`,
  `var(--hs-radius-card)`, `calc(var(--hs-unit) * 3)` for spacing): see `example/dashboards/home/pages/media.module.css`.
- A dashboard folder has no `package.json` of its own — it never adds or changes a dependency.
  `example/package.json` (exact versions, no `^`/`~`, per the root `.npmrc`) covers the whole
  project; if a dashboard genuinely needs a new dependency, that's itself a sign the work belongs
  in `@hashsome/ui` instead (see `add-ui-component`), not a reason to edit that `package.json`.

## When a rule is in the way

If following the user's request seems to require editing `packages/*`, or inventing an import
outside the allowed list: stop, explain what's missing and why it needs a core change, and propose
it as a separate task (`add-ui-component`). Don't quietly work around the restriction inside the
dashboard's own files.

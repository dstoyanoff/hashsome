# Hashsome — smart-home dashboards

pnpm monorepo of React dashboards for Home Assistant (and other integrations).

## Layout

Read [ARCHITECTURE.md](ARCHITECTURE.md) before adding a component, a device kind, or an integration: components take generic entities (`EntityRef | EntityHandle<K>`), and each integration translates its backend to that model.

- `packages/core` — `@hashsome/core`: the `Integration` interface, entity types, wire protocol (no React, no vendor-specific code)
- `packages/integrations/home-assistant`, `packages/integrations/music-assistant` — `@hashsome/integration.home-assistant`, `@hashsome/integration.music-assistant`: installable integration packages, no special treatment from `@hashsome/core`/`@hashsome/runtime`. A third integration follows the same shape (extend `BaseIntegration`, pick a unique `id`) and must export a `./mock` (`createMock`) and run `runIntegrationConformance` from `@hashsome/core/conformance` — `packages/integrations/mock-contract.test.ts` enforces both. Only `hashsome.config.ts` knows which ones are in use.
- `packages/ui` — `@hashsome/ui`: design system (theme tokens in `src/theme/`: palette in `tokens.ts`, density in `density.ts`; styled with one CSS Module per component, `*.module.css` beside it, written in terms of the theme's CSS custom properties `--hs-*`, which `HashsomeProvider` installs: see ARCHITECTURE.md "Styling"), entity components, hooks. `pnpm docs:dev` serves every component in every state on its own standalone dev server — check it before building a dashboard. Icons: any SVG element from an icon library (`lucide-react`, `@tabler/icons-react`, your own), e.g. `icon={<Lightbulb />}`.
- `packages/runtime` — `@hashsome/runtime`: the `hashsome` CLI (`dev`/`build`/`start`) plus reusable pieces (`createLayout`, entry files) that a project's own `app/` imports. It renders no chrome: a dashboard owns its whole layout. Pure library — it has no knowledge of any specific home.
- `example` — `@hashsome/example`: **this is the one real consumer project in this repo**, not a framework piece. It's what a real user's own repo looks like: `hashsome.config.ts` (integrations), a real `app/` (React Router: `root.tsx` and `routes.ts`, which lists every route), `shared/` (conventions the dashboards here reuse — e.g. one `HomeTopBar` and the switcher's dashboard list; not wired into `@hash`), and `dashboards/<id>/` — plain folders (`layout.tsx`, `pages/*.tsx`), not separate packages and not covered by this repo's tests (see Rules below). `example/dashboards/{home,second-floor,kitchen,hello}` are the dogfood dashboards.
- `demo` — `@hashsome/demo`: the example's dashboards (its `routes.ts` points at `example/dashboards/*`) built as a static site with the mock devices running in the browser (`example/shared/mock-entities.ts`), published to GitHub Pages under `/demo/` by `.github/workflows/pages.yml`. A new example dashboard that should be in the demo needs its `route()` added to `demo/app/routes.ts` too.

## Commands

`pnpm install`, `pnpm lint`, `pnpm format:check`, `pnpm typecheck`, `pnpm test`,
`pnpm generate:catalog`. A project updates its `@hashsome/*` packages with `hashsome upgrade` (its `hashsome:upgrade` script). A project is deployed with `hashsome package <plain|compose|helm|image>` (README "Deploying"; mechanics in ARCHITECTURE.md "Packaging and deployment"): the server is bundled into one file and shipped as an image you load onto the host, never built there, and secrets stay in the host's environment. `pnpm dev`/`build`/`start` at the root are thin
`pnpm --filter @hashsome/example <script>` passthroughs — real work happens in that package, same
as it would in a real consumer's own repo.

## Creating or editing a dashboard

Use the skills in `.claude/skills/` — start with `create-dashboard`, which points to the others
(`dashboard-rules`, `entity-discovery`, `ui-catalog`, `verify-dashboard`, `add-ui-component`).
They encode the constraints below plus everything found the hard way while building
`example/dashboards/home` (layout pitfalls and similar) — read them instead
of relying on this summary alone.

## Rules

- Node is pinned via `devEngines.runtime` in package.json and managed by pnpm (`pnpm runtime`); do not use nvm.
- A dashboard is ordinary app code, not a sandbox: it can import anything (other libraries, the project's own `shared/` files, its own helpers). `@hashsome/ui` components are the building blocks, and a dashboard owns its layout — it includes `TopBar`/`NavRail`/`NavDock`/`NavBar` itself, or leaves them out.
- Do not edit `packages/*` when creating a dashboard; propose a separate change instead (see `add-ui-component` for the one deliberate exception, for `packages/ui` only). Registering a new dashboard touches `example/app/routes.ts` (the one file listing every route; plus `example/shared/dashboards.ts` if it should appear in the top bar's switcher) — that's expected, not an exception to this rule, since all of them live inside `example`, not `packages/*`.
- There is no dashboard manifest: a dashboard's URL is its `route('<id>', …)` in `routes.ts`, and its tab title is the entry module's `meta` export.
- Tests (`pnpm test`) cover `packages/*` and `scripts/` only. Don't add tests under `example/dashboards/*` — verify a dashboard with `verify-dashboard` (typecheck plus an actual look at it running) instead.
- `.claude/skills/ui-catalog/CATALOG.md` is generated (`pnpm generate:catalog`) from `packages/ui/src`; never hand-edit it, and regenerate after changing a component's doc comment or props — CI fails if it drifts.
- Work on a feature branch and open a PR; milestones are tracked as GitHub issues.
- **Conventional commits.** Every commit message, and every PR title (CI checks it; PRs are squash-merged, so the title becomes the commit), is `type(scope): description`: `feat` (a new capability, bumps the minor), `fix` (patch), `perf`, or `docs`/`refactor`/`test`/`build`/`ci`/`chore`/`style` (no release). Mark a breaking change with `!` (`feat(ui)!: ...`) or a `BREAKING CHANGE:` footer. The release workflow reads these to choose the version and write the changelog, so say what changed for users, not how.
- Releases are cut by hand: Actions → Release → Run workflow (see ARCHITECTURE.md "Releasing"). All published packages share one version (0.x while alpha; a breaking change is a minor until 1.0). Never edit `version` fields or `CHANGELOG.md` by hand — the workflow does.
- Dependencies use exact versions (no `^`/`~`); `.npmrc` sets `save-exact=true`, so `pnpm add` pins automatically.

---
name: verify-dashboard
description: How to verify a dashboard actually works before calling the task done — checks, plus visually checking it in a browser. Use after creating or editing a dashboard in example/dashboards/*.
---

# Verify a dashboard

Run all of these; don't stop at typecheck passing.

## 1. Automated checks

```bash
pnpm lint
pnpm format:check   # or `pnpm format` to fix
pnpm typecheck
pnpm test
```

`pnpm test` covers `packages/*` only — dashboards (`example/dashboards/*`) have no tests of
their own by design; typecheck plus the real-browser check below is how a dashboard gets verified.
If you scaffolded a new dashboard, confirm you actually did the
registration step from `create-dashboard` (`example/app/routes.ts`) — a dashboard that
typechecks fine but was never registered there simply won't be reachable, and nothing above will
tell you that.

## 2. Look at it in a real browser

The dev server is the only reliable way to catch a layout problem (e.g. a squeezed `ClimateTile`)
— there's no test coverage here to catch it instead.

```bash
pnpm dev
```

Then open `http://localhost:3000/<id>` (and any other page/route it has).

Start it with `HASHSOME_DEBUG=1` (for `dev`, or `build` for a production build) to get a bug button at the
bottom left of the page. Its popover has **Show grid**, which draws the module grid over the page: a faint
line at every spacing unit (4px in the comfortable density), a stronger one at every tile pitch, and a box
round each card with its height in units, green when its top and height sit on the grid and red when they
do not, with a legend at the bottom (it also gives the viewport, the screen and the pixel ratio). The popover
also makes the page **Fullscreen** and changes the **Theme** (light, dark, system or sun). The choices are
kept on the device.

`pnpm dev` at the repo root is a thin `pnpm --filter @hashsome/example dev` passthrough — Vite's
dev server actually runs rooted at `example` and watches `dashboards/*` like any other source
file in that project, no restart needed between edits, just the usual HMR refresh. Two cases are
route-table changes, not plain content edits, and HMR doesn't always pick them up cleanly — do a
hard refresh (or restart `pnpm dev`) after either, and don't read a stale result as a real bug:
registering a brand-new dashboard (added its block to `app/routes.ts`), and
adding a new route to an _existing_ dashboard (also an edit to `app/routes.ts`).

Check, at the screen size it targets:

- Every section/tile you added is present and labelled correctly (no truncated labels, no two
  tiles with the same name).
- At least one interaction round-trips: click a light/scene/action tile, or drag a dimmable one,
  and confirm the state updates (it's reading from the mock or real backend through the actual
  WebSocket proxy, not a static render).
- If there's more than one page (a layout route with a `NavRail`/`NavDock`/`NavBar`), every nav item goes to the right page and
  highlights as current.
- Nothing shows raw text like "unavailable" rendered incorrectly, unstyled, or a blank tile for a
  bad ref — bad refs should read "Not found", not error or vanish.
- Check the browser console for errors (not just the visual result) — a page can look fine while
  logging a real problem underneath (e.g. a WebSocket reconnect loop).

## 3. Production build (only if asked to ship / for a final check)

```bash
pnpm build
pnpm start
```

Open the same routes again. This exercises the SPA fallback and the real bundle, not just dev's
on-the-fly transform — worth doing once before calling a dashboard finished, cheap to skip during
iteration.

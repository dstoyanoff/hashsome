---
name: android-component
description: Add or change a component in the native Android libraries (android/headless and android/ui): the headless control first, then the view, the parity entry, tests and an example demo. Use only when an Android dashboard genuinely needs a component that is missing, and the user has agreed to the library change.
---

# Add an Android component

The deliberate exception to "do not edit `android/*` when creating a dashboard" (see
`create-android-dashboard`). Use it only when a dashboard hits a real gap, not a preference for
different styling, and the user has agreed to the library change as its own change, apart from the
dashboard. Read ARCHITECTURE.md "Native Android" first: the layers, the must-nots and the parity rule
bind everything below.

## Before adding anything

Check `android/PARITY.md`: the web component may be `planned` (then this is the work, and the entry
becomes `native`), `web-only` (then it is by design; ask), or absent (a `native-only` component needs
a reason in `parity.json`). Read the web component in `packages/ui/src` and, if there is one, its hook:
the Kotlin version mirrors its behavior and prop names.

## Steps (in this order)

1. **The headless control first**, in `android/headless/src/main/kotlin/dev/hashsome/headless/`, one
   state holder per TS hook it mirrors (`LightControl`, `MediaPlayerControl`, ...). It holds the state
   as `Store<T>`, takes an `EntityHandle` and a `Scope`, sends commands through it, and imports
   nothing from `android.*` (a build check fails otherwise). Timing (press pending/failed, seek
   hold, drag-then-commit) takes the `Scheduler` it is given; never `Handler` or `System.currentTimeMillis`
   directly. No AndroidX, coroutines or kotlinx.serialization; no vendor-specific code.
2. **Test it on the JVM** in `android/headless/src/test/kotlin/...`, with `android/testing` (`FakeClient`,
   `TestScheduler`): one test file per mirrored hook, covering the web hook's states (loading,
   unavailable, unknown, ready) and its timing. The behavior is already specified by the web hook's
   tests in `packages/ui`; port their cases.
3. **The view** in `android/ui/src/main/kotlin/dev/hashsome/ui/` (`Components.kt` or `Media.kt`...):
   a custom-drawn View bound to the control (`Scope` owns the subscription, so there is nothing to
   unregister by hand). Every color, radius and size is read from the resolved `Theme` when it
   draws; no literals. Only API 24-safe calls: lint runs with `NewApi` fatal. An icon is a resource
   id (`Icon`), never reflection. Allocate nothing in `onDraw`.
4. **The DSL function** in `Dsl.kt` (`PageScope`): the same name and parameter names as the web
   component's props, with defaults, and a KDoc line. Pure layout math, if any (placement, flex), is
   unit-tested on the JVM.
5. **The parity entry**: in `android/parity.json` set the web component's entry to `native` (or
   `native:<Name>` when the Kotlin type is named differently); a native-only component gets
   `native-only` with the reason. Run `pnpm generate:parity` and commit `android/PARITY.md`, then
   `pnpm check:parity`.
6. **Show it in the example**: add the component to a page of `example/android` in every state the
   mock data can produce, so it is looked at on a real display (`create-android-dashboard`, Verify).
   Keep the screenshot beside the web one in `docs/screenshots/android/` if the PR is about looks.
7. **Verify**: `pnpm android:build` (compile, lint, unit tests, layering) and
   `(cd example/android && ./gradlew assembleDebug)`, then `pnpm lint && pnpm format:check &&
pnpm typecheck && pnpm test` for the scripts and CLI, and deploy to a device when the view is the
   point of the change.

## Boundaries

- One component per PR where possible; commit scopes `android-headless` and `android-ui` (a
  `feat(android-ui): ...` releases a minor; say what changed for users).
- If the component needs a protocol or model change, that is a `@hashsome/core` change first, and
  the fixtures (`pnpm generate:fixtures`; it arrives with the client module, WP1 of this milestone) and the Kotlin parsers follow it: stop and describe it.
- Do not widen a module's dependencies to make something fit: `checkDependencies` fails, and the
  layering is the point.

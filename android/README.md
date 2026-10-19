# Hashsome for Android

Native Android libraries that render Hashsome dashboards, as a client of the same runtime the web dashboards use
(`/ws` and `/_hashsome/asset`). The backend, `@hashsome/core` and the integrations are untouched. One consumer app
depends on these libraries and holds its dashboards in Kotlin, the way a web project holds its `dashboards/` folder.

All five modules share one version, `hashsome.version` in `gradle.properties`, which is the npm packages' version. The
release workflow rewrites that line; never edit it by hand.

## Modules

Dependencies point down the table only (`kiosk` -> `ui` -> `headless` -> `client`; `testing` -> `client`). Gradle enforces it:
a module's build file lists the one module under it and nothing else from this repo.

| Module     | Artifact                | Holds                                                                                                                              | Depends on |
| ---------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| `client`   | `dev.hashsome:client`   | `Store`, `Scheduler`, `Transport` and `WsClient`, `Client` and `RemoteClient`, the typed entity models and their parsers, `Images` | nothing    |
| `headless` | `dev.hashsome:headless` | `Scope`, `EntityHandle` and one state holder per control (`LightControl`, `MediaPlayerControl`, ...), the theme schedule           | `client`   |
| `ui`       | `dev.hashsome:ui`       | theme and density, icons, base views, layouts and the page DSL, components, the dashboard host and `DashboardActivity`             | `headless` |
| `kiosk`    | `dev.hashsome:kiosk`    | device profiles, screen, presence, night mode, the foreground service, MQTT bridge to Home Assistant, `Settings` and its sheet     | `ui`       |
| `testing`  | `dev.hashsome:testing`  | `TestScheduler`, `FakeTransport`, `FakeRuntime`, JSON fixtures generated from `@hashsome/core`                                     | `client`   |

`client` is tested on the JVM: its parsers against `testing`'s fixtures (the frames `@hashsome/core`'s mocks put on the wire,
regenerated with `pnpm generate:fixtures`; CI fails when they drift), `WsClient` against a local socket, and `RemoteClient` against
`FakeTransport`/`FakeRuntime` and `TestScheduler`. `RuntimeSmokeTest` talks to a real runtime and is skipped unless you name one:
start `pnpm --filter @hashsome/example dev` (a mock backend, it sends one command to a mock light) and run
`(cd android && ./gradlew :client:testDebugUnitTest --tests '*RuntimeSmokeTest' -Phashsome.smoke=localhost:3000)`.

`ui` never references `kiosk`. It exposes plug points on `HashsomeApp` (`ScreenPolicy`, `TouchListener`, `PageListener`,
with defaults that do nothing) that `kiosk` implements, installed with `HashsomeApp.withKiosk(...)`.

## Must-nots

- No AndroidX, no coroutines, no kotlinx.serialization, in any module. State is `Store<T>` read on the main thread;
  JSON is `org.json`.
- `headless` imports nothing from `android.*` (a check in its build fails if it does): it has to run on a plain JVM.
- Only API 24-safe platform calls (no `java.time`). Lint runs with `NewApi` fatal and `abortOnError`, as part of `build`.
- No reflection for icons: an `Icon` is a resource id.
- Components read every color, radius and size from the resolved `Theme` when they draw: no literals.
- No vendor-specific code in `client` and `headless`; what an entity is comes from the model, never from which integration
  served it.
- Commit scopes: `android`, `android-client`, `android-headless`, `android-ui`, `android-kiosk`.

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0); put `sdk.dir=...` in `android/local.properties` (ignored by
git) or set `ANDROID_HOME`.

Each Gradle build has its own wrapper and there is none at the repo root, so run from inside the directory (CI does the
same with `working-directory: android`):

```sh
(cd android && ./gradlew build)       # compiles all modules, runs lint, the unit tests and the layering checks
(cd example/android && ./gradlew assembleDebug)
```

Publishing goes to GitHub Packages (`https://maven.pkg.github.com/dstoyanoff/hashsome`), credentials from `GITHUB_ACTOR` and
`GITHUB_TOKEN`, or `gpr.user` and `gpr.key` in `~/.gradle/gradle.properties`:

```sh
(cd android && ./gradlew publishAllPublicationsToGitHubPackagesRepository)
```

Each module publishes its `release` AAR with sources, under group `dev.hashsome` and the module's name as the artifact id.

## How `example/android` consumes it

`example/android` is its own Gradle build, like a consumer's repo. Its `settings.gradle.kts` has
`includeBuild("../../android")`, so a dependency on `dev.hashsome:ui` resolves to the `:ui` project of this directory by
group and name, whatever version the catalog names. It is the same mechanism a consumer uses to develop against a local
checkout of this repo; one that wants the published libraries declares the GitHub Packages repository instead and pins
`hashsome` in its `gradle/libs.versions.toml`.

An app's `versionCode` is `major*10000 + minor*100 + patch` of the version, so it grows with every release.

## See also

- [ARCHITECTURE.md](../ARCHITECTURE.md) "Native Android": the layers, the plug points, the protocol rules and how a consumer project is laid out; "Releasing" for how these artifacts are published.
- [PARITY.md](PARITY.md): which `@hashsome/ui` components have a Kotlin counterpart (generated from `parity.json`).
- `hashsome android build|deploy|provision|uninstall|icons` builds and installs an app on the displays in `devices.json`.
- Skills for agents: `.claude/skills/create-android-dashboard` and `.claude/skills/android-component`.

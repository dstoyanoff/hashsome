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

## `kiosk`: restarting from the settings sheet and Home Assistant

- **Restart app** (Connection group; MQTT button `restart_app`): schedules a one-shot alarm 1.5 s ahead that starts `KioskService` (the boot gate of a Shelly profile applies as to any start), then ends the process; the dashboard comes back with the service.
- **Open Shelly app** (Connection group, Shelly profiles only; MQTT button `open_stock_app`): launches the stock app and pauses keep-in-front, and draws a floating **Back to dashboard** button over it (a `TYPE_APPLICATION_OVERLAY` window, `TYPE_PHONE` below API 26; needs the `SYSTEM_ALERT_WINDOW` appop, which `provision` grants). Tapping it ends the pause and brings the dashboard back; pressing and dragging it moves it (a tap that stays within the touch slop is a tap), it stays on the screen, starts in the bottom-right corner 12 dp from the edges, and its last position is kept in `Settings` as fractions of the screen. The pause has no timeout of its own: `KioskConfig.stockAppPauseMs` (default 2 hours) is only a safety cap, and it also ends when the dashboard is resumed by other means. Without the overlay permission there is no button and the pause is the old 10 minutes (logged).
- **Restart device** (Debug group, tap twice; MQTT button `restart_device`): shown only where a reboot is possible, probed once the service runs and again every 5 minutes until a way is found (once found it is not probed again; the reboot itself runs off the main thread): the display's own Shelly RPC (`/rpc/Shelly.Reboot` on 127.0.0.1:80) on a Shelly profile, or `PowerManager.reboot` / the device-owner reboot where the app holds `REBOOT` or is device owner.

## `kiosk`: updating from Home Assistant

The first install of a display is manual, once (`hashsome android provision`, or `provision --system` on the X1; see
the consumer's README for its per-device steps). After that a display updates itself from a **feed**: any folder a plain
HTTP GET can read (Home Assistant's `/config/www`, served at `/local/`, a static host, an object store) holding
`latest.json` and the APK it names.

1. `hashsome android build`, then `hashsome android publish --to <feed folder> --notes "what changed"`. This copies
   the APK to `<package>-<versionCode>.apk` and writes `latest.json` (`packageName`, `versionName`, `versionCode`,
   `apk`, `sha256`, `size`, `notes`).
2. Give each device `"updates": "http://<host>:8123/local/hashsome"` in `devices.json` and provision or deploy once (or
   type it into the settings sheet's "Updates" group). Without a feed nothing is checked and no entity appears.
3. The device checks a minute after start and every six hours. When `versionCode` is higher, Home Assistant's `update`
   entity on the device (and the sheet's "Install update") offers it. It is never installed by itself.
4. Install downloads the APK, checks its size and SHA-256 against `latest.json`, and hands it to the system installer.
   A system app (the X1) installs silently and restarts. Any other (the X2i, a tablet) shows Android's confirmation:
   one tap on the display. The X1 needs one reboot after the first deploy of a build that has the update feature
   (`INSTALL_PACKAGES` is granted to a system app's new permissions only when its /system copy is rescanned).

The "Check for update" button in Home Assistant checks at once. The feed URL needs no authentication, so keep it on the
local network.

## System install on Shelly displays (X1)

On the Shelly Wall Display X1 (Android 7, a **userdebug** image: `ro.debuggable=1`, `adb root` works) the stock Shelly app force-stops every non-system app at each boot (its shutdown hook, `SHELLY APPLICATION SHUTDOWN COMPLETED`), so the app only survives a reboot as a **system app**. This is optional and for Shelly profiles only; a plain tablet keeps the normal install. The X2i is a user build, with no adb root, and cannot do it.

```sh
hashsome android provision --system --device bathroom-small [--home] [--dry-run]
```

It checks the image (`ro.build.type` userdebug or `ro.debuggable` 1), runs `adb root` (and connects again, adbd restarts), checks that `/system` has the APK's size plus 1 MB free, then: remounts `/system` read-write (`mount -o rw,remount /system || mount -o rw,remount /`), `mkdir -p /system/priv-app/Hashsome`, pushes the APK to `/system/priv-app/Hashsome/Hashsome.apk`, `chmod 755` the directory and `644` the APK, `chown root:root`, `restorecon -R` where available, remounts read-only and reboots. The permissions, battery whitelist, device admin and settings broadcast follow after the reboot (the package manager only knows a priv-app after the boot scan), then `dumpsys package <pkg> | grep flags` is printed and must show `SYSTEM`. The procedure is ShellyElevate's `install-privapp.sh` (Apache-2.0, see NOTICE).

- If the app is already installed in `/data/app` with the same signature, it becomes an _updated system app_. A `/data` copy with a lower `versionCode` than the pushed APK is uninstalled first with `pm uninstall -k`, which keeps its data (the APK's version is read with `aapt` from `$ANDROID_HOME/build-tools`; without it the copy stays and the boot scan sorts it out).
- One-time: nothing is recorded in `devices.json`. Later updates are a plain `hashsome android deploy` (`adb install -r` keeps the system flag). Because an update must have a higher `versionCode` to survive a reboot, deploy also keeps the system copy in sync: after the install, if `dumpsys package <pkg>` shows the app as a system app (`SYSTEM` flag) and the image is rootable, it does `adb root`, remounts `/system` read-write, pushes the APK to `/system/priv-app/Hashsome/Hashsome.apk`, `chmod 644`, `chown`, `restorecon`, remounts read-only. No reboot: the running update is already the new code, the copy is for the next boot. Without this, a same-`versionCode` deploy would be dropped at the next reboot for the older system copy; bumping the `versionCode` is not needed. On a system app whose image is not rootable it prints a warning that the update will not survive a reboot. `--dry-run` shows these steps.
- Undo: `hashsome android uninstall --system --device bathroom-small` does the usual uninstall (the `pm uninstall` of a pure system app fails and is tolerated), after the same image and `adb root` check as provision (so a user build fails before anything is removed), remounts read-write, `rm -rf /system/priv-app/Hashsome`, remount read-only and reboot.

## How `example/android` consumes it

`example/android` is its own Gradle build, like a consumer's repo. Its `settings.gradle.kts` has
`includeBuild("../../android")`, so a dependency on `dev.hashsome:ui` resolves to the `:ui` project of this directory by
group and name, whatever version the catalog names. It is the same mechanism a consumer uses to develop against a local
checkout of this repo; one that wants the published libraries declares the GitHub Packages repository instead and pins
`hashsome` in its `gradle/libs.versions.toml`.

The libraries cannot declare `usesCleartextTraffic`, so a consumer whose runtime is plain `http://` sets `android:usesCleartextTraffic="true"` on the `<application>` in its own manifest.

An app's `versionCode` is `major*10000 + minor*100 + patch` of the version, so it grows with every release.

## `ui`: icons and fonts

`ui` draws its own chrome (close, transport, chevrons, search, the weather conditions) with glyphs it bundles; a dashboard's own
icons are the consumer's (`hashsome android icons lucide`). Two generators produce what is checked in, and both are re-run when the
glyph set changes:

1. The chrome glyphs: `android/ui/icons/*.svg` are the lucide-static 1.59.0 files `ui` uses (ISC, `android/ui/icons/LICENSE`).
   To change the set, install `lucide-static` somewhere outside the repo, copy the SVGs in, and from the repo root run

   ```sh
   node packages/runtime/bin/hashsome.ts android icons ui --dir android/ui/icons --out android/ui/src/main/res/drawable \
     --kotlin android/ui/src/main/kotlin/dev/hashsome/ui/icons/Ui.kt --package dev.hashsome.ui --strict
   ```

   It writes `ic_ui_<name>.xml` (tintable) and `object Ui` (`dev.hashsome.ui.icons.Ui`); `UiIcons` in `Icons.kt` names the ones the
   chrome uses.

2. The weather conditions, drawn in several colors like the web's `CONDITION_LOOK` (a tinted drawable has one color):
   `node android/ui/tools/gen-weather-icons.mjs`, after step 1. It reads the converted drawables and writes
   `ic_weather_<condition>.xml` (the parts with a color of their own, baked in) and `ic_weather_<condition>_plain.xml` (the parts
   that take the text color: white, so a tint recolors them), and the Kotlin table `WeatherLook.kt` (condition, glyph, colors,
   label). `iconBitmap` draws a colored glyph untinted, and its plain companion over it in the color it is asked for.

Fonts: Inter Regular, Medium and SemiBold (`android/ui/src/main/assets/fonts`, OFL, see `android/NOTICE`; the Medium face is Latin
only). `HashsomeApp(font = ...)` takes any `FontSource` that returns a `FontSet`.

## See also

- [ARCHITECTURE.md](../ARCHITECTURE.md) "Native Android": the layers, the plug points, the protocol rules and how a consumer project is laid out; "Releasing" for how these artifacts are published.
- [PARITY.md](PARITY.md): which `@hashsome/ui` components have a Kotlin counterpart (generated from `parity.json`).
- `hashsome android build|deploy|provision|uninstall|icons` builds and installs an app on the displays in `devices.json`.
- Skills for agents: `.claude/skills/create-android-dashboard` and `.claude/skills/android-component`.

---
name: create-android-dashboard
description: Write a native Android dashboard with the Kotlin DSL in a consumer's Android app (e.g. example/android), register it, and deploy and check it on a display. Use whenever the user asks for a dashboard or page on an Android wall display or tablet; for the web use create-dashboard.
---

# Create an Android dashboard

The native side renders the same entities as the web, from the same server. A dashboard is a Kotlin
`object` in the consumer's own Android app (`example/android` here, `home/android` for the owner's
home), built with the DSL in `android/ui`. It is ordinary app code, the way a web dashboard is a plain
folder. Read ARCHITECTURE.md "Native Android" once if you have not.

## Rules

- **Do not edit `android/*`** (the libraries) while creating a dashboard, even for a one-line fix. A
  missing component is a separate change: stop, say so, and see `android-component`.
- Everything you add lives in the consumer app: its `src/main/kotlin/<ns>/` (dashboards, shared
  helpers), `devices.json`, and the app's `App.kt` where dashboards are listed.
- The libraries cannot declare `usesCleartextTraffic`: if the runtime is plain `http://`, the consumer's own manifest must set `android:usesCleartextTraffic="true"` on its `<application>`.
- No AndroidX, coroutines or kotlinx.serialization in the app either: observation is
  `Store.listen { }`, which the DSL does for you.
- Entity refs are strings, as on the web: `"ha:light.bathroom_led"`, `"ma:player-id"`. Find real ids
  with `entity-discovery`; do not guess them.
- Check `android/PARITY.md` for what exists natively. A web component marked `planned` has no Kotlin
  counterpart yet: do not hand-build a look-alike inside the dashboard.
- Spacing is in the theme's units (`gap = 3` is the web's `gap={3}`); sizes that are not units are dp.
  No color or radius literals: they come from the theme.

## Steps

1. **Confirm the id, the title, the display(s) and what is on each page.** The id is a kebab-case slug
   (`bathroom`); it is what `Settings.dashboard` stores and what Home Assistant's select offers.
   Know the display's profile (`shelly-stargate` X1, `shelly-jenna` X2i, `generic` tablet) and its
   screen size; slow panels use `Motion.Light`.
2. **Find the real entities** with `entity-discovery`.
3. **Write the dashboard**, copying the closest existing one in the app. Its shape:

   ```kotlin
   import dev.hashsome.ui.Align.Center
   import dev.hashsome.ui.Justify.End

   object Bathroom : Dashboard("bathroom", motion = Motion.Light, idleReturn = false) {
     override val pages = listOf(
       page("", "Bathroom", Lucide.bath) {
         column(gap = 3, grow = true) {
           row(align = Center, justify = End, gap = 3, minHeight = 40) { dateChip(); weatherChip("ha:weather.forecast_home"); clock(); systemStatus() }
           roomHeader("Bathroom", Lucide.bath) { sensorReadout("ha:sensor.bathroom_t_h_sensor_temperature") }
           grid(columns = 2) { lightTile("ha:light.bathroom_led", "Ambient"); lightTile("ha:light.bathroom_lamp", "Lamp") }
           spacer(grow = true)
           mediaPlayerBar(PLAYER, "Bathroom speaker", onOpen = { navigate("music") })
         }
       },
       page("music", "Music", Lucide.music) { column(grow = true) { mediaPlayerColumn(PLAYER, "Bathroom speaker", fill = true) } },
     )
     override fun shell(s: ShellScope) = s.navRail(pages)
   }
   ```

   - The page DSL is `PageScope` in `android/ui/src/main/kotlin/dev/hashsome/ui/Dsl.kt`; read it for
     what exists and each function's parameters (they mirror the web components' props, named the
     same). The web `.claude/skills/ui-catalog/CATALOG.md` documents the semantics of each.
   - `shell` is the chrome around the pages (`navRail`, `topBar`); a dashboard owns its layout and
     may leave it out.
   - Icons come from the app's generated pack: `hashsome android icons lucide` (or `tabler`, or
     `--dir`) writes drawables and an `object Lucide`; it is gitignored, and you run it with the
     app's `pnpm android:icons` (= `hashsome android icons lucide`). Use `Lucide.<name>` or your own `Icon(R.drawable...)`.
   - A refreshing value comes from an entity handle, never from a timer in the dashboard.

4. **Register it**: add it to `dashboards = listOf(...)` in the app's `Hashsome.install(...)` (first is
   the default), and to a `DashboardOption` switcher list if it should appear in the top bar's
   switcher.
5. **Point a display at it**: in `devices.json` (project root) set `"dashboard": "<id>"` on the device.
   Fields: `id`, `host` (`ip` or `ip:port`, adb port 5555 by default), `profile`, and optionally
   `dashboard`, `server` (the Hashsome server URL the display connects to), `mqtt`, `home`
   (make the app the display's HOME). `hashsome android --help` shows the format.
6. **Mock data**: develop against the mock backend (`pnpm dev` without Home Assistant tokens, as for the
   web) so no command reaches the real house. In `example`, add the entities to
   `hashsome.config.ts`'s mock data as `create-dashboard` step 6 describes.
7. **Verify** (below) before calling the task done.

## Deploy and provision

From the project root, with `adb` on the PATH and the display reachable over the network:

```sh
hashsome android build                                  # ./gradlew assembleRelease inside android/
hashsome android deploy --device bathroom               # install, hand over settings, start
hashsome android provision --device bathroom [--home]   # first time only: permissions, battery whitelist, device admin
hashsome android uninstall --device bathroom            # removes the app's own device admin first, then the app
```

On the X1 (userdebug) the app must be a system app or the stock app kills it at every boot: `hashsome android provision --system --device <id>` once (instead of plain `provision`; later updates are plain `deploy`; `uninstall --system` undoes it; a lower-version /data copy is removed with `pm uninstall -k`, keeping its data; refused on a tablet and on the X2i). See `android/README.md` "System install on Shelly displays (X1)".

`uninstall` relies on the app's kiosk module answering `dev.hashsome.REMOVE_ADMIN`; an app built without
`withKiosk` cannot be uninstalled this way while a device admin is active.

Add `--dry-run` to any of them to print the adb/Gradle commands without running them. Provision once
per display; deploy for every later update (`adb install -r` keeps the settings, as long as the app is
signed with the same keystore). Do not run provision or `--home` on a real display without being told
to: it changes which app is the display's home screen.

## Verify

1. `(cd android && ./gradlew build)` if you touched anything in `android/` (you should not have), and
   the app's own build: `cd <app dir>/android && ./gradlew assembleDebug` (`example/android`), or
   `hashsome android build`.
2. `hashsome android deploy --device <id>`, then look at the display: a screenshot with
   `adb -s <host>:5555 exec-out screencap -p > docs/screenshots/android/<id>-<page>.png` and Read it
   (the first bring-up creates `docs/screenshots/android/`; `mkdir -p` it until then).
   Compare with the same page on the web at the display's CSS size (`verify-dashboard`). Look for
   clipped text, a column that does not grow, a tile in the wrong state (loading, unavailable), and
   the page transitions on the real device: the point of the native side is smoothness on slow panels.
3. Every state a tile can be in (on/off, unavailable, loading) appears with the mock data.
4. `adb -s <host>:5555 logcat -d | grep -i hashsome` for crashes. On the Shelly X1 only `Log.e` reaches
   logcat.
5. Leave the changes uncommitted or open a PR as asked; work on a feature branch, per `AGENTS.md`.

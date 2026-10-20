# Installing the app on a display

The first install of a display is manual and happens once, from your computer over ADB. After that the display can update
itself from Home Assistant ([Updates](#updates)). This page is the whole first install; the rest of `android/README.md` is
about building and developing the libraries.

## What you need

- **Your own Android app** that depends on the `dev.hashsome:*` libraries (see `example/android` for a small one). One APK
  serves every display; which dashboard a display shows, where its server is, and its MQTT broker are handed over when it is
  provisioned, never compiled in.
- **`devices.json`** at your project root, one entry per display:

  ```json
  [
    {
      "id": "kitchen",
      "host": "10.1.1.50",
      "profile": "shelly-jenna",
      "dashboard": "kitchen",
      "server": "http://10.1.1.2:3000",
      "mqtt": "10.1.1.2:1883",
      "updates": "oci://10.1.1.3:5000/kitchen-app"
    }
  ]
  ```

  `profile` is `shelly-stargate` (Shelly Wall Display X1), `shelly-jenna` (X2i) or `generic` (any other Android tablet or
  phone). `server` is your Hashsome runtime. `mqtt` (a broker Home Assistant also uses) and `updates` (the update feed, below)
  are optional: leave them out and the display simply has no Home Assistant device or no updates. `home: true` also makes
  the app the display's home screen.

- **`adb`** on your computer, and the display reachable at `host:5555` with ADB over the network on. Both Shelly displays
  answer there; on another device enable ADB over the network in its developer settings. Provisioning changes what runs on
  a display, so do it with someone at it.
- **JDK 17 and the Android SDK** to build (`hashsome android build`; see `android/README.md`).

## Build, then install

```sh
hashsome android build                                  # the one release APK
hashsome android provision --device <id> [--dry-run]    # first install of a display; --dry-run prints every adb command
```

`provision` installs the APK, grants what a kiosk needs (write settings, draw over other apps, usage access, battery
whitelist, a device admin where the profile uses one), hands the display its settings and starts it. Run it once per
display. Every later update from your computer is `hashsome android deploy`.

Per kind of display:

| Display                                        | First install                                       | Notes                                                                                                                                                                                                          |
| ---------------------------------------------- | --------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Shelly Wall Display X2i** (`shelly-jenna`)   | `hashsome android provision --device <id>`          | A normal install. The stock Shelly app does not restart at boot, so the dashboard starts straight after it. Updates ask for one tap on the display.                                                            |
| **Shelly Wall Display X1** (`shelly-stargate`) | `hashsome android provision --system --device <id>` | Its stock app force-stops every non-system app at boot, so the app has to be a **system app**. This needs the display's userdebug image (`adb root` works) and reboots it once. Updates then install silently. |
| **Other Android tablet** (`generic`)           | `hashsome android provision --device <id> [--home]` | `--home` makes the app the home screen; the lock screen is switched off so a sleeping tablet wakes into the dashboard (kept if a PIN is set). Updates ask for one tap.                                         |

A tablet can also be made the **device owner** (`"owner": true` in `devices.json`; Android only allows it on a device with no accounts, so remove
the Google account first). The app then switches the notification pull-down off (a "Block pull-down shade" switch in the sheet and in Home Assistant),
and Home Assistant can restart the device. The sheet's and Home Assistant's "Open tablet settings" button is the way into Android's own settings
then: it pauses keep-in-front and shows a "Back to dashboard" button. `uninstall` gives the device owner up first.

See "System install on Shelly displays (X1)" in `android/README.md` for what `--system` does step by step.

After the first start the display shows its dashboard. If you gave it `mqtt`, Home Assistant offers a device named after the
display (screen, night mode, brightness, dashboard, theme, backend URL and more).

The settings sheet is hidden: press the small status dot at the far right of the top row and hold it for 5 seconds. It holds the same
settings as Home Assistant, plus the connection details and the update feed.

## Updates

A display with an `updates` address checks it for a newer build a minute after it starts and every six hours, and offers it as an
**update entity** on its Home Assistant device. Nothing installs unless you press install (in Home Assistant, or the settings
sheet's "Install update"). The address is one of two kinds, and a release is published with the same command:

```sh
hashsome android build
hashsome android publish --to <where> --notes "what changed"
```

**An OCI registry** (Zot, the Docker registry, anything that speaks the registry API), the way to go when you have one:
`"updates": "oci://10.1.1.3:5000/home-android"` and `--to oci://10.1.1.3:5000/home-android`. The release is pushed as an artifact
tagged with its version code; there is no folder to copy anywhere, and the registry's own retention keeps the latest few. Use the
registry's plain-HTTP LAN address (as a node pulling images does), because a display does not trust your own CA; `ocis://` is for a
registry with a certificate Android trusts. The registry must allow anonymous reads and, for whoever publishes, writes.

**A folder** any HTTP GET can read (Home Assistant's `/config/www`, served at `/local/`, a static web host, an object store):
`"updates": "http://10.1.1.2:8123/local/hashsome"`. `publish --to <folder>` writes `<package>-<versionCode>.apk` and a `latest.json`
(version, checksum, size, notes) into it, and you put that folder where the web server serves it.

The version code comes from your app, so raise it for every release. The display checks the APK's size and SHA-256 against what the
feed says before it hands the file to Android. Neither feed needs credentials: keep them on your local network.

On an X1 installed as a system app the update installs silently and the app restarts. Everywhere else Android asks for one tap on the
display. The first time you deploy a build that has the update feature to an X1, reboot it once so the system grants the app the right
to install.

## Uninstalling

```sh
hashsome android uninstall --device <id> [--system]
```

The app removes its own device admin first (Android refuses to uninstall an app that is one), then it is uninstalled.
`--system` also removes the system copy and reboots.

## When something is wrong

- **Dashboard says "Not found" or the dot is red:** the display cannot reach `server`. Check the address in the settings
  sheet (a change connects at once) or in Home Assistant's "Backend URL" entity.
- **Nothing appears in Home Assistant:** check `mqtt` (host and port of the broker), and that Home Assistant's MQTT
  integration uses the same broker.
- **The stock Shelly app covers the dashboard (X1/X2i):** the dashboard comes back within a couple of seconds on its own; if
  it does not, `provision` was not run for that display.
- **An X1 loses the app after a reboot:** it was installed as a normal app. Run `provision --system` for it.

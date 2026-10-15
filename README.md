# Hashsome

[![CI](https://github.com/dstoyanoff/hashsome/actions/workflows/ci.yml/badge.svg)](https://github.com/dstoyanoff/hashsome/actions/workflows/ci.yml)
[![Component gallery](https://img.shields.io/badge/component%20gallery-live-blue)](https://dstoyanoff.github.io/hashsome/)

**Smart-home dashboards you build like software.** A real design system, real React components and
a small server, in place of a drag-and-drop card editor. You write the dashboard for a wall tablet,
a phone or a small kitchen display as ordinary code, and Hashsome keeps it live, themed and easy to
deploy.

**[Try the live demo →](https://dstoyanoff.github.io/hashsome/demo/)** The example dashboards on mock data, in your browser.
**[Explore the component gallery →](https://dstoyanoff.github.io/hashsome/)** Every component in every state.

> **Alpha.** Hashsome is early software: not every device type is built yet, and what is built has
> only been tried against one real home. See [Status](#status-alpha) for exactly what works.

<table>
  <tr>
    <td width="50%"><img alt="Dashboard, dark" src="docs/screenshots/home-dark.png"></td>
    <td width="50%"><img alt="Dashboard, light" src="docs/screenshots/home-light.png"></td>
  </tr>
  <tr>
    <td align="center"><sub>Dark</sub></td>
    <td align="center"><sub>Light, or follow the system</sub></td>
  </tr>
</table>

## What you get

- **Components that already know your devices.** Give a light tile an entity id and you get on/off,
  dimming, colour and colour temperature, a hold-to-open drawer with history and power use, and
  pending/error feedback. The same goes for climate, sensors, scenes, media players and more. See
  every one in every state in the **[component gallery](https://dstoyanoff.github.io/hashsome/)**.
- **A full media experience.** A compact player bar, an upright card, and a full-page player with
  your library: playlists, albums, artists and search, with artwork, shuffle and seeking.
- **Real history, not placeholders.** Sensor charts, energy use per day, week and month, and
  battery levels come from your backend's own records when it keeps them.
- **Light, dark or follow the system**, plus a compact density for small square displays. Change
  any colour, radius or spacing with one overrides object.
- **Your tokens stay on the server.** The browser talks to Hashsome's server, which talks to your home.
  Nothing secret reaches a tablet.
- **Not tied to one backend.** Home Assistant and Music Assistant work today, side by side.
  See [Works with any backend](#works-with-any-backend).
- **Deploys anywhere**, and builds where you build it, never where it runs. See
  [Deploying](#deploying).
- **Built to be extended by an AI coding agent** as well as by hand. See
  [Build a dashboard](#build-a-dashboard).

<table>
  <tr>
    <td width="50%"><img alt="The full-page media player, dark" src="docs/screenshots/player-dark.png"></td>
    <td width="50%"><img alt="The full-page media player, light" src="docs/screenshots/player-light.png"></td>
  </tr>
  <tr>
    <td colspan="2" align="center"><sub>The full-page media player, with the library docked below it</sub></td>
  </tr>
</table>

## Try it in two minutes

```bash
git clone https://github.com/dstoyanoff/hashsome.git
cd hashsome
corepack enable        # gets you the right pnpm
pnpm install
pnpm dev
```

Open **http://localhost:3000/home**. It runs on mock data, so there is something to click without a
smart home connected. The other examples are at `/second-floor`, `/kitchen` and `/hello`.

For the component gallery on its own: `pnpm docs:dev`.

### Connect your home

Point it at a real Home Assistant and/or Music Assistant with environment variables. They are
independent: set either, both or neither, and anything unset keeps using mock data.

```bash
HA_URL=http://homeassistant.local:8123 HA_TOKEN=<a long-lived access token> \
MA_URL=http://mass.local:8095 MA_TOKEN=<a token from Settings → Profile> \
pnpm dev
```

In Home Assistant, create the token under your profile → **Long-lived access tokens**. For a token
that belongs to the dashboard and not to you, create a separate non-admin user for it first.
Music Assistant is talked to directly over its own API, not through Home Assistant.

## Build a dashboard

A dashboard is a folder of React under your project's `dashboards/`, and a route in
`app/routes.ts` makes it reachable at `/<id>`. You compose the page from `@hashsome/ui`:

```tsx
import { ClimateTile, Grid, LightTile, RoomHeader, SensorReadout } from '@hashsome/ui';

export const meta = () => [{ title: 'Living room' }];

export default function LivingRoom() {
  return (
    <>
      <RoomHeader
        title="Living room"
        icon="lu:sofa"
        readouts={<SensorReadout entity="ha:sensor.living_room_temperature" />}
      />
      <Grid columns={2}>
        <LightTile entity="ha:light.living_room_lamp" name="Lamp" />
        <ClimateTile entity="ha:climate.living_room" name="Heater" />
      </Grid>
    </>
  );
}
```

```ts
// app/routes.ts: every URL in one place
route('living-room', '../dashboards/living-room/page.tsx'),
```

Devices are addressed as `<integration>:<id>`, such as `ha:light.kitchen` or `ma:kitchen`. A
dashboard owns its whole layout: add the top bar, a left navigation rail or a bottom dock, or leave
them out for a bare kiosk panel. Icons are plain strings (`'lu:lightbulb'`, `'tb:vacuum-cleaner'`).

While you lay a dashboard out on the device it is for, start with `HASHSOME_DEBUG=1` (for `dev`, or when
building: a production build has the menu only if it was built with the flag). A bug button at the bottom
left then opens a menu that shows the module grid over the page, makes the page fullscreen and switches
the theme (light, dark, system, and sun if the project names a sun entity: `theme={{ sun: … }}` or the provider's `sun`). What it sets is kept on that device.

### Or ask your agent

If you use Claude Code or another coding agent, the repo ships the skills it needs. Once it is
running, ask for what you want:

> Create a dashboard for my kitchen. It's a tablet on the wall. Devices: the ceiling light, a LED
> strip, the thermostat, and a "cooking time" scene.

The agent scaffolds the dashboard, looks up your real entity ids (or uses placeholders until you
connect a backend), composes the page from the design system, wires up the route and checks that
it renders. The skills live in `.claude/skills/`, and they are also good reading for doing the
same by hand.

## Works with any backend

The components never talk to a specific system. Each backend has an **integration** that turns it
into a small, generic model (a light, a climate device, a media player, a sensor, ...) and turns
the UI's commands back into that backend's own. Your project lists the ones it uses:

```ts
// hashsome.config.ts
import { HomeAssistantIntegration } from '@hashsome/integration.home-assistant';
import { MusicAssistantIntegration } from '@hashsome/integration.music-assistant';
import { defineConfig } from '@hashsome/runtime';

export default defineConfig({
  integrations: [
    new HomeAssistantIntegration({ url: process.env.HA_URL!, token: process.env.HA_TOKEN! }),
    new MusicAssistantIntegration({ url: process.env.MA_URL!, token: process.env.MA_TOKEN! }),
  ],
});
```

This is why one dashboard can show a Home Assistant light next to a Music Assistant player, and
why adding a new backend means writing one integration package and touching nothing else. Anything
you build for the UI keeps working, whichever system sits behind it. The contract and how it fits
together are in **[ARCHITECTURE.md](ARCHITECTURE.md)**.

## Deploying

Build once on your own machine. Nothing is compiled, and no source is copied, where it runs.

```bash
pnpm package helm --platform linux/amd64     # or: compose, plain, image
```

`hashsome package` builds your dashboards, bundles the server with your config and integrations
into one file, and writes what you need into `./release`:

| Target    | What you get                                               | Needs                  |
| --------- | ---------------------------------------------------------- | ---------------------- |
| `plain`   | `server.mjs` and `client/`: run it with `node server.mjs`  | Node 24+ where it runs |
| `compose` | a Docker Compose file and the container image              | Docker where it runs   |
| `helm`    | a Helm chart for k3s/Kubernetes and the container image    | k3s/Kubernetes + helm  |
| `image`   | only the container image, for manifests you write yourself | a container runtime    |

Set a default in `hashsome.config.ts` so `pnpm package` takes no flags:

```ts
export default defineConfig({
  integrations: [...],
  package: { targets: ['helm'], platform: 'linux/amd64' },
});
```

**Secrets are never in the image or the release files.** The server reads `HA_URL`, `HA_TOKEN` and
the rest from its environment when it runs: an `.env` file for compose, a Kubernetes Secret for
helm. No registry is involved: copy `release/`, load `image.tar` and start it. Point a tablet's
browser or a kiosk app at `http://<the server>:3000/<id>`.

How the release is built, and how to load the image on k3s, is in
[ARCHITECTURE.md](ARCHITECTURE.md#packaging-and-deployment).

### A wall display that only opens a Home Assistant

Some wall displays, such as the Shelly Wall Display, only accept an address that answers like a Home
Assistant: they run its login flow before they show a page. One line in `hashsome.config.ts` makes
Hashsome answer those checks (the login flow, `/api/`, `/api/config`, `/manifest.json`), in `pnpm dev`
and in a packaged release:

```ts
export default defineConfig({
  integrations: [...],
  homeAssistantCompat: true,
});
```

Then give the display your server's address as its "Home Assistant" and any username and password.
It is off by default. It accepts any login, because nothing sits behind it (see Security below), and
it is written from Home Assistant's documented API, not from any device's firmware, so a firmware
update could change what a display asks for.

### On a phone's home screen

A dashboard can be added to an iPhone's (or Android phone's) home screen and open without the browser's bars,
like an app. Give the shell your app's name and icon in `app/root.tsx`, and put the icon and a manifest in your
project's `public/` folder:

```tsx
export const Layout = createLayout('dark', 'Inter', { name: 'Home', icon: '/icon-180.png' });
```

```json
{
  "name": "Home",
  "short_name": "Home",
  "start_url": "/",
  "display": "standalone",
  "background_color": "#101114",
  "theme_color": "#101114",
  "icons": [{ "src": "/icon-512.png", "sizes": "512x512", "type": "image/png" }]
}
```

Then, in Safari, Share → Add to Home Screen. Hashsome has no login of its own, so reach it from outside the house
only over a VPN (see Security below), not by publishing it.

## Security

- **Dashboards have no login.** A Hashsome server shows and controls everything its integrations
  expose to anyone who can reach it. Run it on a trusted network, or put it behind a reverse proxy
  that authenticates (Authelia, Authentik, Cloudflare Access, basic auth, a VPN). Do not expose it to
  the internet as is.
- **`homeAssistantCompat` accepts any login** on the Home Assistant login endpoints, and hands out a
  token that opens nothing. It adds no access that Hashsome does not already give to anyone who can
  reach it; turn it on only for a server on a network you trust.
- **Tokens stay on the server**, read from its environment: never in the browser, the image or the
  release files. Where you can, give Home Assistant a dedicated non-admin user for the token.
- To report a vulnerability, see [SECURITY.md](SECURITY.md).

## Staying up to date

A project runs `pnpm hashsome:upgrade` (a script in its `package.json`, which calls `hashsome upgrade`)
to move every `@hashsome/*` package to the latest release together, with pnpm. On another
package manager the command prints what to run by hand. Packages linked from a local checkout are left
alone, and `--dry-run` shows the command without running it. Read the [release notes](https://github.com/dstoyanoff/hashsome/releases) for what changed, then run your typecheck.

## Packages

Published to npm under the `@hashsome` scope, all at the same version.

| Package                                                                                                        | What it is for                                                                                                                                  |
| -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| [`@hashsome/ui`](https://www.npmjs.com/package/@hashsome/ui)                                                   | The design system: themed components for lights, climate, sensors, scenes and media players, layout pieces, hooks and the theme tokens.         |
| [`@hashsome/runtime`](https://www.npmjs.com/package/@hashsome/runtime)                                         | The `hashsome` command (`dev`, `build`, `start`, `package`), the server that talks to your backends, and the app entry files your project uses. |
| [`@hashsome/core`](https://www.npmjs.com/package/@hashsome/core)                                               | The generic entity model, the integration contract and the wire protocol. No React, nothing vendor-specific; what you build an integration on.  |
| [`@hashsome/integration.home-assistant`](https://www.npmjs.com/package/@hashsome/integration.home-assistant)   | Connects Home Assistant: lights, climate, sensors, scenes, media players, people, weather and history.                                          |
| [`@hashsome/integration.music-assistant`](https://www.npmjs.com/package/@hashsome/integration.music-assistant) | Connects Music Assistant directly: players, queues, library browsing and search.                                                                |

A project depends on `ui` and `runtime`, plus one integration per backend. `core` comes along with
those, and you only import it yourself to write an integration.

## Layout

```
packages/        the packages above, one folder each (integrations under packages/integrations/)
example/         a complete project you can run, and the template for your own
demo/            the example's dashboards as a static site on mock data (the live demo)
docs/            screenshots
```

Everything under `example/` is an ordinary project that uses the framework, the same as yours
would. Where each piece lives, and why, is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Development

```bash
pnpm lint             # oxlint
pnpm format:check     # oxfmt (pnpm format to fix)
pnpm typecheck
pnpm test             # vitest, every package
pnpm generate:catalog # regenerate the component catalog after changing a component
```

Node is pinned in `package.json` and managed by pnpm, so no nvm is needed; `corepack enable` gets
the matching pnpm. More detail on the rules is in [`AGENTS.md`](AGENTS.md), which Claude Code also
reads as `CLAUDE.md`.

## Status: alpha

Hashsome is **alpha**. It runs a real home today, but expect rough edges, and expect things to change.

- **Not every device type is implemented.** The table below is the whole list. Anything else shows
  up as a generic, read-only value, or not at all.
- **Not fully tested.** The framework has an automated test suite (components, integrations, the
  server and the packaging), and it is used daily against one real setup: Home Assistant and
  Music Assistant. It has not been tried across many devices, brands or setups, so integrations will
  meet states nobody has hit yet.
- **Interfaces can change.** Component props, the integration contract and the packaging output
  may change between versions. The packages are on npm at 0.x, but there is no scaffolder yet:
  the easiest start is still to clone the repository and copy a project like `example/`.

### What works today

| Device type  | Component(s)                           | From Home Assistant                                   | What it does                                                                                                                                                |
| ------------ | -------------------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Light        | `LightTile`                            | `light`                                               | On/off, brightness, colour temperature, colour with presets; a drawer with power and energy charts when you point it at the sensors, and recent activity    |
| Climate      | `ClimateTile`                          | `climate`                                             | Mode, target temperature stepper, presets, current temperature; a drawer with power and energy charts when you point it at the sensors, and recent activity |
| Sensor       | `SensorReadout`                        | `sensor`, `binary_sensor`                             | Value and unit, safe-range warnings, a drawer with a history chart, min/max and the battery level                                                           |
| Switch, lock | `ActionButton`                         | `switch`, `fan`, `input_boolean`, `lock`              | Toggle only                                                                                                                                                 |
| Action       | `SceneButton`, `ActionButton`, top bar | `scene`, `script`, `button`, `automation`, `vacuum`   | Trigger it, with pending, done and error feedback                                                                                                           |
| Media player | `MediaPlayerBar`, `Column`, `Page`     | `media_player` (and Music Assistant players directly) | Play/pause, previous/next, volume, mute, seek, shuffle; the library with browse and search; artwork                                                         |
| Person       | top bar                                | `person`                                              | Presence avatars: who is home, with their picture                                                                                                           |
| Weather      | top bar                                | `weather`                                             | The current condition and temperature, today's high and low, and a forecast drawer (next 24 hours, days ahead)                                              |

Other kinds of device, such as blinds and covers, cameras, alarm panels, locks with their own
controls, fans with speeds, `select` and `number` entities, are not built
yet. They are modelled generically where the backend allows it, and have no dedicated component.
New device types are welcome: see [ARCHITECTURE.md](ARCHITECTURE.md#adding-things).

Milestones and open work are tracked as [GitHub issues](https://github.com/dstoyanoff/hashsome/issues).

## Contributing

Issues, ideas and pull requests are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md). Questions go in
[Discussions](https://github.com/dstoyanoff/hashsome/discussions).

## License

[GPL-3.0](LICENSE).

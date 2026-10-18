import {
  Bath,
  Bed,
  Brush,
  Fan,
  Footprints,
  House,
  Lamp,
  LampDesk,
  LayoutGrid,
  Lightbulb,
  Martini,
  Moon,
  Music,
  Pipette,
  RefreshCw,
  Sofa,
  Sparkles,
  Sun,
  Thermometer,
  Trash2,
  Tv,
  UtensilsCrossed,
  Wrench,
} from 'lucide-react';
import { LocalClient } from '@hashsome/core';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { NavLink, Route, Routes } from 'react-router';
import { ActionButton } from '../entities/action-button.tsx';
import { ClimateTile } from '../entities/climate-tile.tsx';
import { LightTile } from '../entities/light-tile.tsx';
import { MediaBrowser } from '../entities/media-browser.tsx';
import { MediaPlayerBar } from '../entities/media-player-bar.tsx';
import { AnimatedOutlet } from '../layout/animated-outlet.tsx';
import { MediaPlayerColumn } from '../entities/media-player-column.tsx';
import { ListTabs, MediaPlayerFull, type ListTab } from '../entities/media-player-full.tsx';
import { MediaQueue } from '../entities/media-queue.tsx';
import { MediaSpeakers } from '../entities/media-speakers.tsx';
import { WeatherForecast } from '../entities/weather-forecast.tsx';
import { NavBar } from '../entities/nav-bar.tsx';
import { NavDock } from '../entities/nav-dock.tsx';
import { NavRail } from '../entities/nav-rail.tsx';
import { Page } from '../layout/page.tsx';
import { SceneButton } from '../entities/scene-button.tsx';
import { SensorReadout } from '../entities/sensor-readout.tsx';
import {
  Clock,
  DateChip,
  PresenceStack,
  SystemStatus,
  TopBar,
  WeatherChip,
} from '../entities/top-bar.tsx';
import { Icon } from '../icon.tsx';
import {
  EnergyChart,
  type EnergyRange,
  type EnergySample,
  type EnergyUsage,
} from '../layout/energy-chart.tsx';
import { AnimatedNumber } from '../layout/animated-number.tsx';
import { Board, Cell } from '../layout/board.tsx';
import { Grid } from '../layout/grid.tsx';
import { HistorySection } from '../layout/history-section.tsx';
import { RoomHeader } from '../layout/room-header.tsx';
import { ConfirmDialog } from '../layout/confirm-dialog.tsx';
import { TaskTile } from '../layout/task-tile.tsx';
import { Tile } from '../layout/tile.tsx';
import { TopRow } from '../layout/top-row.tsx';
import type { LogbookEntry } from '../layout/history-section.tsx';
import { HashsomeProvider } from '../provider.tsx';
import { createGalleryIntegration } from './fixtures.ts';
import styles from './gallery.module.css';
import { Flex, Typography } from './layout.tsx';
import { COMPONENT_PROPS } from './props-data.ts';

const ICON_PACKS = [
  { name: 'Lucide', package: 'lucide-react', href: 'https://lucide.dev/icons/' },
  { name: 'Tabler', package: '@tabler/icons-react', href: 'https://tabler.io/icons' },
];

const NAV_ITEMS = [
  { to: '', label: 'Home', icon: <House /> },
  { to: 'lights', label: 'Lights', icon: <Lightbulb /> },
  { to: 'climate', label: 'Climate', icon: <Thermometer /> },
  { to: 'music', label: 'Music', icon: <Music /> },
];

/** The same pages with something to look at on two of them: a warm dot (`notice`) and a red one (`urgent`). */
const ATTENTION_ITEMS = NAV_ITEMS.map((item) =>
  item.to === 'lights'
    ? { ...item, attention: 'notice' as const }
    : item.to === 'climate'
      ? { ...item, attention: 'urgent' as const }
      : item,
);

/** A bounded "stage" card rendering real `@hashsome/ui` components on the app background, so the demos
 * read as they would on a dashboard against the gallery's own page chrome. */
function Stage({ children }: { children: ReactNode }) {
  return <div className={styles.stage}>{children}</div>;
}

/** One documented component: a heading, a one-line description, and a `Stage` demoing its
 * supported states — interactive, not just a static picture. */
/** A component's props, read from its own prop interface and doc comments (`props-data.ts`, generated
 * by `pnpm generate:catalog`), so what's listed here is exactly what the IDE shows on hover. */
function PropsTable({ name }: { name: string }) {
  const entry = COMPONENT_PROPS[name];
  if (!entry || entry.props.length === 0) {
    return null;
  }

  return (
    <details className={styles.props}>
      <summary>
        <Typography as="span" variant="label" color="textMuted">
          Props · {name} ({entry.props.length})
        </Typography>
      </summary>
      <table>
        <thead>
          <tr>
            {['Prop', 'Type', 'Description'].map((heading) => (
              <th key={heading} scope="col">
                <Typography as="span" variant="eyebrow" uppercase color="textMuted">
                  {heading}
                </Typography>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {entry.props.map((prop) => (
            <tr key={prop.name}>
              <td>
                <Typography as="span" variant="label" className={styles.code}>
                  {prop.name}
                  {prop.optional ? '' : ' *'}
                </Typography>
              </td>
              <td>
                <Typography as="span" variant="secondary" color="textMuted" className={styles.code}>
                  {prop.type}
                </Typography>
              </td>
              <td>
                <Typography as="span" variant="body" color="textMuted">
                  {prop.doc}
                </Typography>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </details>
  );
}

function ComponentDoc({
  title,
  description,
  components,
  children,
}: {
  title: string;
  description: string;

  /** Names of the components this section documents; each gets a props table under the demo. */
  components: string[];
  children: ReactNode;
}) {
  return (
    <section className={styles.section}>
      <Flex direction="column" gap={1} maxWidth="60ch" mb={4}>
        <Typography as="h2" variant="heading">
          {title}
        </Typography>
        <Typography as="p" variant="body" color="textMuted">
          {description}
        </Typography>
      </Flex>
      <Stage>{children}</Stage>
      {components.map((name) => (
        <PropsTable key={name} name={name} />
      ))}
    </section>
  );
}

/** A day `n` days from today, at the start of it. */
const inDays = (n: number) => {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate() + n);
};

/** What marking something done takes in a demo: a moment, and nothing else. */
const pause = () => new Promise<void>((resolve) => setTimeout(resolve, 600));

function ListTabsDemo() {
  const [tab, setTab] = useState<ListTab>('queue');
  return (
    <Flex direction="column" width={380}>
      <ListTabs value={tab} lists={['queue', 'library']} onChange={setTab} />
    </Flex>
  );
}

function ConfirmDialogDemo() {
  const [open, setOpen] = useState(false);
  const [answer, setAnswer] = useState<string>();
  return (
    <Flex align="center" gap={4}>
      <ActionButton
        label="Empty the trash?"
        icon={<Trash2 />}
        onPress={async () => setOpen(true)}
      />
      <Typography as="span" variant="body" color="textMuted">
        {answer ?? 'Not asked yet'}
      </Typography>
      <ConfirmDialog
        open={open}
        title="Empty the trash?"
        description="Nothing in it can be brought back."
        confirmLabel="Empty it"
        onConfirm={() => {
          setAnswer('Emptied');
          setOpen(false);
        }}
        onCancel={() => {
          setAnswer('Left alone');
          setOpen(false);
        }}
      />
    </Flex>
  );
}

/** A reading that changes every time the button is pressed, to see it move. */
function AnimatedNumberDemo() {
  const [watts, setWatts] = useState(40.1);
  return (
    <Flex align="center" gap={4}>
      <Typography as="span" variant="stat">
        <AnimatedNumber value={watts} format={(n) => `${n.toFixed(1)} W`} />
      </Typography>
      <ActionButton
        label="Change the reading"
        icon={<RefreshCw />}
        onPress={() => {
          // Down to a few watts, then back up to the lamp's own.
          setWatts((current) => (current > 20 ? 3.2 : 38) + Math.round(Math.random() * 60) / 10);

          return Promise.resolve();
        }}
      />
    </Flex>
  );
}

/** Plain dimmable tile whose fill follows a local value, since `Tile` itself is controlled. */
function TileFillDemo() {
  const [fill, setFill] = useState(0.6);
  return (
    <Tile
      label="With fill"
      icon={<Sun />}
      active
      fill={fill}
      onFillChange={setFill}
      secondary={`${Math.round(fill * 100)}%`}
    />
  );
}

/** A labeled sub-preview within a `Stage`, for a component that isn't itself a tile (e.g. nav
 * chrome). `NavDock` is `position: fixed`, which a plain wrapper can't contain — `transform` on
 * an ancestor makes it establish a containing block for fixed-position descendants instead (a
 * standard CSS escape hatch), confining it to this box instead of the real viewport. */
const DEMO_PAGES = [
  { to: '', label: 'Lights', icon: <Lightbulb /> },
  { to: 'climate', label: 'Climate', icon: <Thermometer /> },
  { to: 'music', label: 'Music', icon: <Music /> },
] as const;

const DEMO_BASE = '/gallery/transition';

/** Three pages under a route of the gallery's own router, with links to go between them: later ones
 * slide in from the right. Until the address is under the demo's route there is a link to start it. */
function AnimatedOutletDemo() {
  return (
    <Flex direction="column" gap={3} height={140} p={3}>
      <Routes>
        <Route
          path={DEMO_BASE}
          element={
            <>
              <Flex gap={2}>
                {DEMO_PAGES.map((page) => (
                  <NavLink key={page.to} to={page.to ? `${DEMO_BASE}/${page.to}` : DEMO_BASE} end>
                    {({ isActive }) => (
                      <Flex
                        as="span"
                        align="center"
                        px={3}
                        height={32}
                        radius="full"
                        background={isActive ? 'accent' : 'surfaceRaised'}
                        color={isActive ? 'accentText' : 'text'}
                      >
                        <Typography as="span" variant="label">
                          {page.label}
                        </Typography>
                      </Flex>
                    )}
                  </NavLink>
                ))}
              </Flex>
              <AnimatedOutlet items={[...DEMO_PAGES]} base={DEMO_BASE} />
            </>
          }
        >
          {DEMO_PAGES.map((page) => (
            <Route
              key={page.to}
              {...(page.to ? { path: page.to } : { index: true })}
              element={
                <Flex center grow={1} background="surfaceRaised" radius="row">
                  <Typography as="span" variant="heading">
                    {page.label}
                  </Typography>
                </Flex>
              }
            />
          ))}
        </Route>
        <Route
          path="*"
          element={
            <Flex center grow={1}>
              <NavLink to={DEMO_BASE}>Try the transition</NavLink>
            </Flex>
          }
        />
      </Routes>
    </Flex>
  );
}

function SubPreview({
  label,
  height,
  children,
}: {
  label: string;
  height: number;
  children: ReactNode;
}) {
  return (
    <Flex direction="column" gap={2}>
      <Typography as="span" variant="eyebrow" uppercase color="textMuted">
        {label}
      </Typography>
      <Flex
        position="relative"
        overflow="hidden"
        radius="row"
        border
        height={height}
        style={{ transform: 'translateZ(0)' }}
      >
        {children}
      </Flex>
    </Flex>
  );
}

// Hand-written activity, to show the drawer's History section through the `history` prop. Tiles
// without it (and with a backend that keeps a record) fetch their own.
const lampHistory: LogbookEntry[] = [
  {
    id: '1',
    message: 'turned on',
    actor: 'Dan',
    timestamp: new Date(Date.now() - 2 * 60_000).toISOString(),
  },
  {
    id: '2',
    message: 'turned off',
    actor: 'automation.bedtime',
    actorKind: 'automation',
    timestamp: new Date(Date.now() - 60 * 60_000).toISOString(),
  },
  {
    id: '3',
    message: 'turned on',
    actor: 'Dan',
    timestamp: new Date(Date.now() - 3 * 60 * 60_000).toISOString(),
  },
];

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

const stairsHistory: LogbookEntry[] = [
  {
    id: '1',
    message: 'turned off',
    actor: 'automation.bedtime',
    actorKind: 'automation',
    timestamp: ago(45),
  },
  { id: '2', message: 'turned on', actor: 'Alex', timestamp: ago(4 * 60) },
  { id: '3', message: 'turned off', actor: 'Alex', timestamp: ago(9 * 60) },
];

const ledHistory: LogbookEntry[] = [
  { id: '1', message: 'brightness set to 60%', actor: 'Dan', timestamp: ago(12) },
  { id: '2', message: 'turned on', actor: 'Dan', timestamp: ago(14) },
  {
    id: '3',
    message: 'turned off',
    actor: 'automation.morning_routine',
    actorKind: 'automation',
    timestamp: ago(6 * 60),
  },
];

const heaterHistory: LogbookEntry[] = [
  { id: '1', message: 'target set to 17 °C', actor: 'Dan', timestamp: ago(25) },
  {
    id: '2',
    message: 'mode set to heat',
    actor: 'automation.morning_routine',
    actorKind: 'automation',
    timestamp: ago(7 * 60),
  },
  { id: '3', message: 'mode set to off', actor: 'Alex', timestamp: ago(14 * 60) },
];

const nightLampHistory: LogbookEntry[] = [
  {
    id: '1',
    message: 'turned off',
    actor: 'automation.bedtime',
    actorKind: 'automation',
    timestamp: ago(30),
  },
  { id: '2', message: 'color changed', actor: 'Alex', timestamp: ago(90) },
  { id: '3', message: 'turned on', actor: 'Alex', timestamp: ago(2 * 60) },
];

// Same story as the history demo data above — no real integration exposes energy history yet
// (see the same ticket); a small deterministic wave stands in so the chart has something to show.
function generateSeries(points: number, intervalMs: number, base: number, amplitude: number) {
  const now = Date.now();
  return Array.from({ length: points }, (_, i) => ({
    timestamp: new Date(now - (points - 1 - i) * intervalMs).toISOString(),
    watts: Math.max(
      0,
      Math.round(base + amplitude * Math.sin(i / 3) + Math.sin(i * 7) * amplitude * 0.15),
    ),
  }));
}

const lampEnergyByRange: Partial<Record<EnergyRange, EnergySample[]>> = {
  '1h': generateSeries(30, 2 * 60_000, 9, 2),
  '1d': generateSeries(48, 30 * 60_000, 8, 4),
  '1w': generateSeries(28, 6 * 60 * 60_000, 7, 5),
  '1m': generateSeries(30, 24 * 60 * 60_000, 8, 6),
};

// Demo usage totals (kWh) — real ones need a history/statistics source no integration provides yet.
const lampUsage = { today: 0.18, week: 1.1, month: 4.7, last7d: 1.3, last30d: 5.2 };

/** Mock live data for the lamp's power sensor: a small random walk every few seconds, also
 * appended to the 1H series, so the drawer's headline and sparkline visibly move. */
function useLiveLampEnergy(ha: ReturnType<typeof createGalleryIntegration>) {
  const [oneHour, setOneHour] = useState(lampEnergyByRange['1h'] ?? []);

  useEffect(() => {
    let watts = 9;
    const id = setInterval(() => {
      watts = Math.min(14, Math.max(5, Math.round(watts + (Math.random() - 0.5) * 3)));
      ha.update('sensor.lamp_power', { value: String(watts), numeric: watts });
      setOneHour((series) =>
        [...series, { timestamp: new Date().toISOString(), watts }].slice(-60),
      );
    }, 2500);

    return () => clearInterval(id);
  }, [ha]);

  return useMemo(
    () => ({
      power: 'ha:sensor.lamp_power' as const,
      energy: 'ha:sensor.lamp_energy' as const,
      usage: lampUsage,
      seriesByRange: { ...lampEnergyByRange, '1h': oneHour },
    }),
    [oneHour],
  );
}

/** Mock power for a climate entity: near `maxWatts` (with a little jitter) while it isn't off, 0
 * while it is — re-evaluated on every mode change and every few seconds, so switching a mode in the
 * card or drawer visibly moves the drawer's power reading. */
function useClimateEnergy(
  ha: ReturnType<typeof createGalleryIntegration>,
  config: {
    entity: string;
    power: string;
    energy: string;
    maxWatts: number;
    series: Partial<Record<EnergyRange, EnergySample[]>>;
    usage: EnergyUsage;
  },
) {
  const { entity, power, energy, maxWatts, series, usage } = config;
  const [oneHour, setOneHour] = useState(series['1h'] ?? []);

  useEffect(() => {
    const tick = () => {
      const device = ha.getEntity(entity);
      const off = device?.kind === 'climate' && device.mode === 'off';
      const watts = off ? 0 : Math.round(maxWatts * (0.9 + Math.random() * 0.2));
      ha.update(power, { value: String(watts), numeric: watts });
      setOneHour((points) =>
        [...points, { timestamp: new Date().toISOString(), watts }].slice(-60),
      );
    };

    const unsubscribe = ha.subscribe(entity, tick);
    const id = setInterval(tick, 2500);
    return () => {
      unsubscribe();
      clearInterval(id);
    };
  }, [ha, entity, power, maxWatts]);

  return useMemo(
    () => ({
      power: `ha:${power}` as const,
      energy: `ha:${energy}` as const,
      usage,
      seriesByRange: { ...series, '1h': oneHour },
    }),
    [power, energy, usage, series, oneHour],
  );
}

const heaterSeries: Partial<Record<EnergyRange, EnergySample[]>> = {
  '1h': generateSeries(30, 2 * 60_000, 1180, 140),
  '1d': generateSeries(48, 30 * 60_000, 700, 600),
  '1w': generateSeries(28, 6 * 60 * 60_000, 600, 500),
  '1m': generateSeries(30, 24 * 60 * 60_000, 650, 450),
};

const thermostatSeries: Partial<Record<EnergyRange, EnergySample[]>> = {
  '1h': generateSeries(30, 2 * 60_000, 20, 20),
  '1d': generateSeries(48, 30 * 60_000, 500, 450),
  '1w': generateSeries(28, 6 * 60 * 60_000, 450, 400),
  '1m': generateSeries(30, 24 * 60 * 60_000, 480, 380),
};

const heaterUsage = { today: 6.2, week: 38, month: 142, last7d: 41, last30d: 148 };
const thermostatUsage = { today: 0.9, week: 11.5, month: 47, last7d: 12.3, last30d: 49 };

// Demo sensor history — no integration exposes it yet (same ticket). 35 days at 30-minute spacing,
// a daily cycle plus a slower drift, so every period (today / week / month / custom) has data.
function generateSensorHistory(
  base: number,
  daily: number,
  drift: number,
): { timestamp: string; value: number }[] {
  const step = 30 * 60_000;
  const count = 35 * 48;
  const now = Date.now();
  return Array.from({ length: count }, (_, i) => {
    const t = now - (count - 1 - i) * step;
    const hours = (t / 3_600_000) % 24;
    const days = t / 86_400_000;
    const value =
      base +
      daily * Math.sin((hours / 24) * 2 * Math.PI) +
      drift * Math.sin(days / 2.3) +
      Math.sin(i * 1.7) * 0.4;

    return { timestamp: new Date(t).toISOString(), value: Math.round(value * 10) / 10 };
  });
}

const temperatureHistory = generateSensorHistory(19.5, 2.2, 1.6);
const humidityHistory = generateSensorHistory(52, 9, 11);
const bedroomHumidityHistory = generateSensorHistory(34, 7, 6);
const officeHumidityHistory = generateSensorHistory(46, 5, 4);

const LED_MAX_WATTS = 12;

/** Mock power for the dimmable LED strip that tracks its brightness: watts scale with the
 * brightness percentage (0 when off), so dragging the slider visibly moves the reading. */
function useBrightnessLinkedEnergy(ha: ReturnType<typeof createGalleryIntegration>) {
  const [oneHour, setOneHour] = useState(() => generateSeries(30, 2 * 60_000, 7, 1));

  useEffect(
    () =>
      ha.subscribe('light.dimmable', (light) => {
        const on = light?.kind === 'light' && light.on;
        const watts = on ? Math.round((light.brightness ?? 1) * LED_MAX_WATTS) : 0;
        ha.update('sensor.led_power', { value: String(watts), numeric: watts });
        setOneHour((series) =>
          [...series, { timestamp: new Date().toISOString(), watts }].slice(-60),
        );
      }),
    [ha],
  );

  return useMemo(
    () => ({
      power: 'ha:sensor.led_power' as const,
      energy: 'ha:sensor.led_energy' as const,
      usage: { today: 0.09, week: 0.62, month: 2.4, last7d: 0.7, last30d: 2.6 },
      seriesByRange: { ...lampEnergyByRange, '1h': oneHour },
    }),
    [oneHour],
  );
}

/**
 * Documentation for every `@hashsome/ui` component: what it is and the states it supports, each a
 * live, interactive demo backed by an in-browser mock — not a dashboard itself (that's what
 * `example/dashboards/*` are for). Served standalone by `pnpm --filter @hashsome/ui docs`. Must
 * be rendered inside a router (nav components use router links).
 */
export function Gallery({ density = 'comfortable' }: { density?: 'comfortable' | 'compact' }) {
  const ha = useMemo(() => createGalleryIntegration(), []);
  const client = useMemo(() => new LocalClient([ha]), [ha]);
  const lampEnergy = useLiveLampEnergy(ha);
  const ledEnergy = useBrightnessLinkedEnergy(ha);
  const heaterEnergy = useClimateEnergy(ha, {
    entity: 'climate.heater',
    power: 'sensor.heater_power',
    energy: 'sensor.heater_energy',
    maxWatts: 1200,
    series: heaterSeries,
    usage: heaterUsage,
  });

  const thermostatEnergy = useClimateEnergy(ha, {
    entity: 'climate.off',
    power: 'sensor.thermostat_power',
    energy: 'sensor.thermostat_energy',
    maxWatts: 1500,
    series: thermostatSeries,
    usage: thermostatUsage,
  });

  const [mode, setMode] = useState<'dark' | 'light'>('dark');
  return (
    <HashsomeProvider client={client} theme={mode} density={density}>
      <div className={styles.page}>
        <div className={styles.wrap}>
          <header className={styles.header}>
            <Flex direction="column" gap={1.5} maxWidth="60ch">
              <Typography as="h1" variant="stat">
                @hashsome/ui component gallery
              </Typography>
              <Typography as="p" variant="body" color="textMuted">
                Every component `@hashsome/ui` ships, with the states it supports — a live,
                interactive reference for building a dashboard, not a dashboard of its own.
              </Typography>
            </Flex>
            <button
              type="button"
              className={styles.modeToggle}
              onClick={() => setMode((m) => (m === 'dark' ? 'light' : 'dark'))}
              aria-label={`Switch to ${mode === 'dark' ? 'light' : 'dark'} theme`}
              aria-pressed={mode === 'dark'}
            >
              <span className={styles.thumb} data-dark={mode === 'dark'} />
              <Icon size={14}>
                <Sun />
              </Icon>
              <Icon size={14}>
                <Moon />
              </Icon>
            </button>
          </header>

          <ComponentDoc
            title="Board & Cell"
            components={['Board', 'Cell']}
            description="A page for one known device, laid out on a grid. Each cell says only how big it is (`cols` wide, `rows` tall); the board places them in order, in the first spot they fit. Rows are as tall as their content, so a cell of `rows={2}` ends where the second row does, and `rows='fill'` runs from where the cell is placed down to the bottom of the page, whatever rows are beside it. Every card is a whole number of grid modules tall, the gap between columns and inside a cell is three modules and between rows six, so the result stays on the grid (turn on the debug menu's Show grid to see it)."
          >
            <Flex direction="column" gap={5}>
              {/* The player covers the two rows beside it, and ends where the second does. */}
              <Board>
                <Cell cols={8}>
                  <RoomHeader title="Kitchen" icon={<UtensilsCrossed />} />
                  <Grid columns={2}>
                    <Tile label="Lamp" icon={<Lightbulb />} />
                    <Tile label="Ceiling" icon={<Lightbulb />} />
                  </Grid>
                </Cell>
                <Cell cols={4} rows={2}>
                  <MediaPlayerColumn entity="ha:media_player.off" />
                </Cell>
                <Cell cols={8}>
                  <RoomHeader title="Stairs" icon={<Footprints />} />
                  <Grid columns={2}>
                    <Tile label="Lamp" icon={<Lightbulb />} />
                    <Tile label="Spotlights" icon={<Lightbulb />} />
                  </Grid>
                </Cell>
              </Board>

              {/* A page as tall as a small display, so `fill` has a bottom to reach. */}
              <Flex direction="column" height={460} style={{ outline: '1px dashed currentColor' }}>
                <Board>
                  <Cell>
                    <TopRow>
                      <DateChip />
                      <Clock format="24h" />
                    </TopRow>
                  </Cell>
                  <Cell cols={8}>
                    <RoomHeader title="Kitchen" icon={<UtensilsCrossed />} />
                    <Grid columns={2}>
                      <Tile label="Lamp" icon={<Lightbulb />} />
                      <Tile label="Ceiling" icon={<Lightbulb />} />
                    </Grid>
                  </Cell>
                  <Cell cols={4} rows="fill">
                    <MediaPlayerColumn entity="ha:media_player.off" />
                  </Cell>
                </Board>
              </Flex>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Top Row"
            components={['TopRow']}
            description="The row along the top of a page you build yourself instead of with `TopBar`: the date, the weather, the clock and the status in a line at the right, in the height the grid gives the top row (40px), with no padding of its own."
          >
            <TopRow>
              <DateChip />
              <Clock format="24h" />
            </TopRow>
          </ComponentDoc>

          <ComponentDoc
            title="Grid"
            components={['Grid']}
            description="Lays tiles out in equal-width columns (`columns`, default 2). Cells share the width and never overflow it."
          >
            <Flex direction="column" gap={5}>
              <Grid columns={2}>
                <Tile label="Two" icon={<LayoutGrid />} secondary="columns={2}" />
                <Tile label="Columns" icon={<LayoutGrid />} secondary="equal width" />
              </Grid>
              <Grid columns={3}>
                <Tile label="Three" icon={<LayoutGrid />} secondary="columns={3}" />
                <Tile label="Columns" icon={<LayoutGrid />} secondary="equal width" />
                <Tile label="Wide" icon={<LayoutGrid />} secondary="fits the row" />
              </Grid>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Room Header"
            components={['RoomHeader']}
            description="The header that starts a room: a muted icon and title, a hairline and readouts on the right, placed above a Grid (or any tiles). It adds its own space above, so consecutive rooms read as groups."
          >
            <Flex direction="column" gap={4}>
              <RoomHeader title="Kitchen" icon={<UtensilsCrossed />} />
              <RoomHeader
                title="Porch"
                icon={<House />}
                readouts={
                  <>
                    <SensorReadout entity="ha:sensor.temperature" />
                    <SensorReadout entity="ha:sensor.humidity" />
                  </>
                }
              />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Tile"
            components={['Tile']}
            description="The base card every entity tile is built from: icon, label, status line, optional fill bar (drag to change it), trailing controls, a hold-to-open drawer and pending / done / error feedback. Use it directly for something that isn't an entity."
          >
            <Grid>
              <Tile label="Plain" icon={<Lamp />} secondary="Off" />
              <Tile label="Active" icon={<Lamp />} active />
              <TileFillDemo />
              <Tile
                label="With controls"
                icon={<Fan />}
                trailing={
                  <Typography as="span" variant="secondary" color="textMuted">
                    trailing
                  </Typography>
                }
              />
              <Tile label="Pending" icon={<Lamp />} feedback="pending" />
              <Tile label="Error" icon={<Lamp />} feedback="error" />
              <Tile label="Unavailable" icon={<Lamp />} status="unavailable" />
              <Tile label="Not found" icon={<Lamp />} status="missing" />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Task Tile"
            components={['TaskTile']}
            description="A recurring task (a filter to change, an additive to add) as a tile: its name, where it stands (up to date, due soon, overdue) and when it is due. Whoever has the task decides the state, since how soon is 'soon' differs between a weekly task and a yearly one. Tap one: it asks whether it is done, and for the day it was done on (today, or an earlier one if it was forgotten), and only a confirmation calls `onComplete`. Marking one done here only waits a moment."
          >
            <Grid>
              <TaskTile
                label="Filter"
                icon={<Wrench />}
                state="ok"
                dueAt={inDays(20)}
                lastDoneAt={inDays(-10)}
                onComplete={pause}
              />
              <TaskTile
                label="Additive"
                icon={<Pipette />}
                state="dueSoon"
                dueAt={inDays(2)}
                lastDoneAt={inDays(-5)}
                onComplete={pause}
              />
              <TaskTile
                label="Salt"
                icon={<Martini />}
                state="overdue"
                dueAt={inDays(-3)}
                lastDoneAt={inDays(-10)}
                onComplete={pause}
              />
              <TaskTile
                label="Not found"
                icon={<Wrench />}
                state="ok"
                status="missing"
                onComplete={pause}
              />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Confirm Dialog"
            components={['ConfirmDialog']}
            description="A question that has to be answered before something is done, so a stray tap cannot do it. It is drawn over the whole page, with a way to back out (Cancel, a tap outside, Escape) and a button to go ahead; more to ask for (a date, say) goes inside it."
          >
            <ConfirmDialogDemo />
          </ComponentDoc>

          <ComponentDoc
            title="Icon"
            components={['Icon']}
            description="Draws any SVG icon element the way the app does (the density's icon size, a thin outline, the surrounding text color): `<Icon><Lightbulb /></Icon>`, with the icon imported from the library you like (Lucide, Tabler, or your own). Props that take an `icon` take the element too: `icon={<Lightbulb />}`. `size` overrides the size (default is the density's)."
          >
            <Flex direction="column" gap={4}>
              <Flex align="center" gap={4}>
                <Icon>
                  <Lightbulb />
                </Icon>
                <Icon size={16}>
                  <Thermometer />
                </Icon>
                <Icon size={32}>
                  <Sofa />
                </Icon>
                <Icon>
                  <Brush />
                </Icon>
                <Icon size={16}>
                  <Fan />
                </Icon>
              </Flex>
              <Flex align="center" gap={4} wrap>
                <Typography as="span" variant="body" color="textMuted">
                  Find an icon:
                </Typography>
                {ICON_PACKS.map((pack) => (
                  <Typography key={pack.name} as="span" variant="body">
                    <a className={styles.link} href={pack.href} target="_blank" rel="noreferrer">
                      Search {pack.name} icons (<span className={styles.code}>{pack.package}</span>)
                    </a>
                  </Typography>
                ))}
              </Flex>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Energy Chart"
            components={['EnergyChart']}
            description="A power section for a drawer, which tiles pass through `energy`: a live power reading, usage tiles and a sparkline. Expanded in a drawer it becomes the full chart with a range switcher."
          >
            <EnergyChart
              power="ha:sensor.heater_power"
              energy="ha:sensor.heater_energy"
              usage={heaterUsage}
              seriesByRange={heaterSeries}
            />
          </ComponentDoc>

          <ComponentDoc
            title="History Section"
            components={['HistorySection']}
            description="Recent activity for a drawer, which tiles pass through `history`: who or what changed the entity, and when. An empty list renders nothing."
          >
            <HistorySection entries={lampHistory} />
          </ComponentDoc>

          <ComponentDoc
            title="Light Tile"
            components={['LightTile']}
            description="Tap to toggle, hold to open its detail drawer. Dimmable lights drag to set brightness; color-capable lights add a palette button that opens swatches in the card (white temperatures by default, overridable with `colors`), and the card's icon shows the light's current color. The drawer has the full temperature and hue bars."
          >
            <Grid>
              <LightTile entity="ha:light.plain_on" energy={lampEnergy} history={lampHistory} />
              <LightTile entity="ha:light.plain_off" history={stairsHistory} />
              <LightTile
                entity="ha:light.dimmable"
                icon={<Sparkles />}
                energy={ledEnergy}
                history={ledHistory}
              />
              <LightTile entity="ha:light.color" icon={<LampDesk />} history={nightLampHistory} />
              <LightTile entity="ha:light.cct" icon={<LampDesk />} />
              <LightTile entity="ha:light.unavailable" />
              <LightTile entity="ha:light.missing" />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Climate Tile"
            components={['ClimateTile']}
            description="A heater/thermostat tile: a mode button that opens mode swatches in the card (like light colors), plus a target-temperature stepper. Hold for the drawer: modes, a target bar with fine +/- (hold to repeat), presets, energy and history."
          >
            <Grid columns={1}>
              <ClimateTile
                entity="ha:climate.heater"
                energy={heaterEnergy}
                history={heaterHistory}
              />
              <ClimateTile
                entity="ha:climate.off"
                energy={thermostatEnergy}
                history={heaterHistory}
              />
              <ClimateTile entity="ha:climate.unavailable" />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Animated Number"
            components={['AnimatedNumber']}
            description="A number that moves to its new value instead of jumping: a power reading, a temperature. The first value is shown at once; a new one is moved to from what is shown now, even if the last move has not finished. The text is changed directly, with no render for each step, so many of them cost little, and nothing moves when the app is set to reduced motion. `format` writes a number with its unit and should round, as it is called for each step. The drawer's power reading, the sensor readouts and a thermostat's current temperature use it."
          >
            <AnimatedNumberDemo />
          </ComponentDoc>

          <ComponentDoc
            title="Sensor Readout"
            components={['SensorReadout']}
            description="An inline value with icon and unit, defaulting its icon by device class. Readings outside a safe range get a warning (humidity defaults to 30–60 %; override with `safeRange`). Pass `history` to make it tappable: the drawer charts it with min/max for today, this week and this month, and a custom date and time range when expanded."
          >
            <Grid columns={3}>
              <SensorReadout entity="ha:sensor.temperature" history={temperatureHistory} />
              <SensorReadout entity="ha:sensor.humidity" history={humidityHistory} />
              <SensorReadout entity="ha:sensor.bedroom_humidity" history={bedroomHumidityHistory} />
              <SensorReadout entity="ha:sensor.office_humidity" history={officeHumidityHistory} />
              <SensorReadout entity="ha:sensor.unavailable" />
              <SensorReadout entity="ha:sensor.unknown" />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Scene Button & Action Button"
            components={['SceneButton', 'ActionButton']}
            description="A scene is just an action entity. An action button triggers an action (or flips a switch) and shows pending / done / error feedback after a press; `onPress` runs your own logic instead."
          >
            <Grid columns={3}>
              <SceneButton entity="ha:scene.tv_time" />
              <ActionButton label="Shower" icon={<Bath />} entity="ha:script.shower" />
              <ActionButton
                label="Always Fails"
                onPress={() => Promise.reject(new Error('That went wrong'))}
              />
            </Grid>
          </ComponentDoc>

          <ComponentDoc
            title="Media Player Bar"
            components={['MediaPlayerBar']}
            description="Track info (with a small shuffle toggle by the title) and the time beside it, then volume, browse, previous, play/pause and next (the artwork opens the browser too), with a thin progress line along the bottom while something plays. The browse button opens the player's own library in the drawer; pass `browse` content to replace it, or `false` for no button. The volume button opens a volume slider in the bar (with a mute swatch, like the light colors and climate modes). Shows album art, or a note glyph when there is none. In a space narrower than 560px, or with `rows={2}`, the controls move to a second row under the track."
          >
            <Flex direction="column" gap={3}>
              <MediaPlayerBar entity="ha:media_player.living_room" />
              <MediaPlayerBar entity="ha:media_player.off" />
              {/* Narrower than 560px it is two rows by itself; `rows={2}` asks for them at any width. */}
              <Flex width={380}>
                <MediaPlayerBar entity="ha:media_player.living_room" rows={2} />
              </Flex>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Media Player Column"
            components={['MediaPlayerColumn']}
            description="The player as an upright card for a narrow column beside a dashboard: the artwork in a ring that shows how far along playback is (drag around it to seek), title, transport, and a volume bar that is always visible. The browse button opens the library in the drawer."
          >
            <Flex width={320}>
              <MediaPlayerColumn entity="ha:media_player.living_room" />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Media Player Full"
            components={['MediaPlayerFull', 'MediaQueue']}
            description="The big player: the player centered with its queue beside it (`MediaQueue`: tap a track to jump to it, the cross takes it out, Clear empties it), and its library below, where every track can be played, played next or added to the queue, and an open album or playlist can be played, shuffled or added whole. The media cards' drawers show the player and library too. Where the queue has no room beside the player (a narrow column, the side drawer), Library and Queue are tabs above the list; `tabs={false}` leaves only the library, `defaultTab` and `tab` / `onTabChange` choose which is open. A dashboard puts it in a page of its own, around it whatever it likes."
          >
            {/* No fixed height: it fills whatever it is given, so here it is as tall as what it shows,
                with no spare room under it. */}
            <Flex direction="column">
              <MediaPlayerFull
                entity="ha:media_player.living_room"
                wide
                queue={<MediaQueue entity="ha:media_player.living_room" />}
                browser={<MediaBrowser entity="ha:media_player.living_room" layout="theater" />}
              />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="List Tabs"
            components={['ListTabs']}
            description="Library and Queue as two tabs with a line under the open one that slides to the next, for a page that lays the player, the queue and the library out itself (`MediaPlayerFull` has them built in). `lists` are the tabs and their order; the page shows the open one's list below. Try them."
          >
            <ListTabsDemo />
          </ComponentDoc>

          <ComponentDoc
            title="Weather Forecast"
            components={['WeatherForecast']}
            description="A weather entity's forecast: the weather now, the next 24 hours as a chart (hover it for everything about an hour) and the days ahead. The top bar's weather chip shows it in a drawer; a dashboard can put it in a page of its own. Narrow, then with `expanded` (readings tiles, a card for each hour)."
          >
            <Flex direction="column" gap={6}>
              <Flex direction="column" width={380}>
                <WeatherForecast entity="ha:weather.home" expanded={false} />
              </Flex>
              <WeatherForecast entity="ha:weather.home" expanded />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Media Speakers"
            components={['MediaSpeakers']}
            description="Who plays together with a player: its stream's speakers, each with its own volume and a Remove button (a follower can also Leave, or remove the player that leads it to carry on itself, and a leader with several others can Remove all), the speakers playing something else (Join their stream, or Add them to this one), the quiet ones it can add (Add). Playing something else on a speaker, or on this player, to do it asks first. Everything is asked of the player and shown when it answers. `speakers` is an allowlist of the ones that can be added: only those that are in it and that the player can be grouped with are offered, and a speaker that is but is off shows dimmed. The player components show it behind a small pill under the track info (it says “Speakers”, “3 speakers” in a group, or “Patio playing” when there is a stream to join) that opens it in the side panel, unless `grouping={false}`. The first demo is a stream led by the porch with the bedroom in it; the second is a player on its own with an allowlist. They are live: try them."
          >
            <Flex direction="column" gap={6}>
              <Flex direction="column" width={560}>
                <MediaSpeakers entity="ha:media_player.porch" />
              </Flex>
              <Flex direction="column" width={560}>
                <MediaSpeakers
                  entity="ha:media_player.office"
                  speakers={[
                    'ha:media_player.porch',
                    'ha:media_player.patio',
                    'ha:media_player.garage',
                  ]}
                />
              </Flex>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Media Browser"
            components={['MediaBrowser']}
            description="A player's own library in the app's style: tap a folder to open it, a track or station to play it, or the play button on an album, playlist or artist for all of it. The search box appears when the library can be searched; with `search='icon'` (the second demo) it is an icon at the end of the row of tabs, after a thin line, and pressing it turns that row into the field, so it takes no room of its own. The `theater` layout (third demo) is one row of large cards that scrolls sideways, for a wide space; `list` is the compact default. While a shelf loads, placeholders take the room the cards will (fourth demo, a shelf that never arrives), so nothing moves."
          >
            <Flex direction="column" gap={6}>
              <Flex direction="column" maxHeight={420} overflow="auto">
                <MediaBrowser entity="ha:media_player.living_room" />
              </Flex>
              <Flex direction="column" maxHeight={420} overflow="auto">
                <MediaBrowser entity="ha:media_player.living_room" search="icon" />
              </Flex>
              <MediaBrowser entity="ha:media_player.living_room" layout="theater" />
              {/* Changing tab shows placeholders in the room the cards will take, so the library does not
                  change height while it loads. Here the shelf never arrives. */}
              <MediaBrowser entity="ha:media_player.loading" layout="theater" />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Navigation"
            components={['NavRail', 'NavDock', 'NavBar', 'Page']}
            description="Routes within one dashboard. A dashboard includes one itself, usually in its layout route: NavRail is fixed to the left, NavDock floats at the bottom and NavBar is the rail's design on its side, a bar along the bottom from edge to edge for a small display where the rail would take too much width; each reserves the space they take in the Page around them, which pads so content never sits under them. An item can carry a dot (`attention`: `notice` or `urgent`) when something on its page needs a look, so it is seen without opening the page."
          >
            <Flex direction="column" gap={5}>
              <SubPreview label="NavRail, in a Page" height={240}>
                <Page height="100%">
                  <NavRail base="/gallery" items={NAV_ITEMS} />
                  <Typography as="p" variant="body" color="textMuted">
                    Page content, padded clear of the rail.
                  </Typography>
                </Page>
              </SubPreview>
              <SubPreview label="NavDock" height={140}>
                <NavDock base="/gallery" items={NAV_ITEMS} />
              </SubPreview>
              <SubPreview label="NavBar, in a Page" height={200}>
                <Page height="100%">
                  <NavBar base="/gallery" items={NAV_ITEMS} />
                  <Typography as="p" variant="body" color="textMuted">
                    Page content, padded clear of the bar.
                  </Typography>
                </Page>
              </SubPreview>
              <SubPreview label="NavRail, with something to look at on two pages" height={240}>
                <Page height="100%">
                  <NavRail base="/gallery" items={ATTENTION_ITEMS} />
                  <Typography as="p" variant="body" color="textMuted">
                    A warm dot says something is coming up, a red one that it needs doing now.
                  </Typography>
                </Page>
              </SubPreview>
              <SubPreview label="NavDock, with something to look at on two pages" height={140}>
                <NavDock base="/gallery" items={ATTENTION_ITEMS} />
              </SubPreview>
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Animated Outlet"
            components={['AnimatedOutlet']}
            description="A dashboard layout's `Outlet` with a transition between its pages: the page that was showing fades out, then the new one fades in, sliding from the side it sits on in the nav (a later page from the right, an earlier one from the left) when you give it the nav's `items`. Without them the pages only fade. It does nothing for a visitor who prefers reduced motion. Try the links."
          >
            <AnimatedOutletDemo />
          </ComponentDoc>

          <ComponentDoc
            title="Top Bar"
            components={['TopBar']}
            description="Page chrome above a dashboard's content: a title (a dropdown to switch dashboards, once there's more than one to switch to), scene shortcuts, weather (tap it for the forecast), presence, and a clock."
          >
            <TopBar
              title="Living Room"
              dashboards={[
                { id: 'living-room', title: 'Living Room', icon: <Sofa /> },
                { id: 'bedroom', title: 'Bedroom', icon: <Bed /> },
                { id: 'bathroom', title: 'Bathroom', icon: <Bath /> },
              ]}
              scenes={[{ entity: 'ha:scene.tv_time', icon: <Tv /> }]}
              weather="ha:weather.home"
              people={['ha:person.dan', 'ha:person.alex']}
            />
          </ComponentDoc>

          <ComponentDoc
            title="Header pieces"
            components={['DateChip', 'WeatherChip', 'Clock']}
            description="The top bar's date, weather and clock, on their own: for a dashboard that builds its own header (a small room display, say) without the title, switcher and scenes of a whole TopBar. Put them in a row with SystemStatus, PresenceStack or anything else."
          >
            <Flex align="center" gap={3} px={4}>
              <DateChip />
              <WeatherChip entity="ha:weather.home" />
              <Clock format="24h" />
            </Flex>
          </ComponentDoc>

          <ComponentDoc
            title="Presence Stack"
            components={['PresenceStack']}
            description="Overlapping avatar circles for a handful of person entities (the Top Bar's presence), each with a picture or a colored initial."
          >
            <PresenceStack entities={['ha:person.dan', 'ha:person.alex']} />
          </ComponentDoc>

          <ComponentDoc
            title="System Status"
            components={['SystemStatus']}
            description="The dot at the top bar's right edge. Green when the runtime and every integration are up, amber while something is connecting, red when something is down; click it for per-system detail. Shown here open, in each state."
          >
            <Flex gap={6} px={6} align="flex-start" justify="space-around" minHeight={32}>
              <SystemStatus
                defaultOpen
                link="open"
                statuses={{ ha: 'connected', ma: 'connected' }}
              />
              <SystemStatus
                defaultOpen
                link="open"
                statuses={{ ha: 'connected', ma: 'connecting' }}
              />
              <SystemStatus
                defaultOpen
                link="open"
                statuses={{ ha: 'error', ma: 'disconnected' }}
              />
            </Flex>
          </ComponentDoc>
          <ComponentDoc
            title="Motion"
            components={['Motion']}
            description="Says how much a dashboard moves, for the display it is made for: put it in the dashboard's layout. A wall display's takes `light` (the cheap animations that play as something appears), a tablet's or a phone's can have `full`, and `reduced` has none. It draws nothing, lasts while the dashboard is shown, and the app's own setting comes back after. `?motion=` on the address still wins, for trying one out."
          >
            <Typography as="span" variant="body" color="textMuted">
              {`<Motion level="light" />`}
            </Typography>
          </ComponentDoc>
          <ComponentDoc
            title="Hashsome Provider"
            description="The app provider: connects to the runtime, installs the theme, font and density globally, and renders the shared detail drawer. Everything on this page runs inside one — use the toggle above to flip its theme."
            components={['HashsomeProvider']}
          >
            <Typography as="span" variant="body" color="textMuted">
              {`theme="${mode}" · density="${density}"`}
            </Typography>
          </ComponentDoc>
        </div>
      </div>
    </HashsomeProvider>
  );
}

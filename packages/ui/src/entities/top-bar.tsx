import { Check, ChevronDown, Palette, Sun } from 'lucide-react';
import type { ConnectionStatus, EntityRef, LinkStatus } from '@hashsome/core';
import { m } from '../motion/index.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { fallbackName } from '../entity-handle.ts';
import {
  useConnectionStatus,
  useEntity,
  useEntityHandle,
  useIntegrationStatuses,
} from '../hooks.ts';
import { Icon } from '../icon.tsx';
import { useDismiss } from '../layout/use-dismiss.ts';
import { statusLabels } from '../status.ts';
import { CheckMark } from '../layout/check-mark.tsx';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import { useTimed } from '../layout/use-timed.ts';
import { useWeatherForecast } from '../use-weather-forecast.ts';
import { SensorReadout } from './sensor-readout.tsx';
import { lazyExport } from '../layout/lazy.tsx';
import { WeatherIcon } from './weather-icon.tsx';
import styles from './top-bar.module.css';
import { CONDITION_LOOK } from './weather-look.tsx';

const WeatherForecast = lazyExport<{ entity: EntityRef }>(
  () => import('./weather-forecast.tsx'),
  'WeatherForecast',
);

/** One entry in the title's dashboard-switcher dropdown. */
export interface DashboardOption {
  /** Matches the dashboard route id — switching navigates to `/{id}`, always its root. */
  id: string;
  title: string;
  icon?: ReactNode;
}

export interface TopBarScene {
  entity: EntityRef;
  icon?: ReactNode;

  /** A literal CSS color for this scene's dot (the one place a color is not from the theme: scenes are meant to be told apart by their own) (e.g. `#8B5CF6`) — scenes are meant to read as distinct at a glance, which the palette doesn't have enough colors for, so each app picks its own. Defaults to the theme's accent. */
  color?: string;
}

/** The colors presence avatars without a picture fall back to when `presenceColors` isn't given. */
const DEFAULT_PRESENCE_COLORS = ['#8B5CF6', '#34D399', '#60A5FA', '#F472B6', '#FBBF24', '#FB923C'];

/** Picks one of `colors` for `id`, the same one every time (a presence avatar's color). */
function colorForId(id: string, colors: readonly string[]): string {
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) >>> 0;
  }

  return colors[hash % colors.length]!;
}

export interface TopBarProps {
  /** The dashboard's name. Becomes a dashboard switcher when `dashboards` has two or more entries. */
  title: string;

  /** Other dashboards to switch to from the title. Under 2 entries renders a plain title. */
  dashboards?: DashboardOption[];

  /** Compact circular buttons that fire `scene.turn_on` directly (not the `Tile`-based `SceneButton`); a bare entity ref uses the default icon and an auto-assigned color, or pass `{ entity, icon, color }` to pick either. */
  scenes?: (EntityRef | TopBarScene)[];

  /** Current weather as a pill with an icon for the sky and a rounded whole-degree reading. A weather entity, like `ha:weather.forecast_home`, shows its real condition, today's high and low once its forecast has loaded, and opens the forecast (the next 24 hours and the days ahead) when tapped, if its source has one; a plain sensor, e.g. an outdoor temperature, gets a fixed sun and no forecast. */
  weather?: EntityRef;

  /** Your own component(s) for the right-hand side, after the weather: a security mode picker, a custom status. Whatever it is, it sits in the bar's row and is yours to style. */
  extra?: ReactNode;

  /** A sensor reflecting overall home/away status (e.g. a binary presence sensor), shown as a chip. */
  presence?: EntityRef;

  /** Individual people, as refs like `ha:person.dan`, shown as an overlapping avatar stack; anyone who is away is dimmed. */
  people?: EntityRef[];

  /** Avatars show the person's own picture; this only colors the initial of those without one — each person gets one color from the list, always the same one. Optional: a built-in set of six is used unless you want your own, e.g. `['#8B5CF6', '#34D399']`. */
  presenceColors?: readonly string[];

  /** Default `true`. */
  showClock?: boolean;

  /** Short date pill before the weather. Default `true`. */
  showDate?: boolean;

  /** `'auto'` follows the browser's locale (browsers don't read the OS 12/24h setting, so an `en-US` browser shows AM/PM); `'12h'`/`'24h'` force it. Default `'auto'`. */
  clockFormat?: 'auto' | '12h' | '24h';

  /** A status dot at the far right — green when the runtime and every integration are up; click it
   * for the details. Default `true`. */
  showStatus?: boolean;
}

/** Shared chrome above a dashboard page's content: title (optionally a dashboard switcher), scene
 * shortcuts, and live status (weather, presence, a clock). Composes freely — every prop but
 * `title` is optional. */
export function TopBar({
  title,
  dashboards,
  scenes,
  weather,
  extra,
  presence,
  people,
  presenceColors,
  showClock = true,
  showDate = true,
  clockFormat = 'auto',
  showStatus = true,
}: TopBarProps) {
  return (
    <div className={styles.bar}>
      <div className={styles.left}>
        {dashboards && dashboards.length > 1 ? (
          <DashboardSwitcher title={title} dashboards={dashboards} />
        ) : (
          <h1 className={styles.title}>{title}</h1>
        )}
        {scenes?.length ? (
          <div className={styles.scenes}>
            {scenes.map((scene) => {
              const { entity, icon, color } = typeof scene === 'string' ? { entity: scene } : scene;
              return (
                <SceneDot
                  key={entity}
                  entity={entity}
                  {...(color ? { color } : {})}
                  {...(icon ? { icon } : {})}
                />
              );
            })}
          </div>
        ) : null}
      </div>
      <div className={styles.right}>
        {showDate ? <DateChip /> : null}
        {weather ? <WeatherChip entity={weather} /> : null}
        {extra}
        {presence ? (
          <StatusChip>
            <SensorReadout entity={presence} drawer={false} />
          </StatusChip>
        ) : null}
        {people?.length ? (
          <PresenceStack
            entities={people}
            {...(presenceColors ? { colors: presenceColors } : {})}
          />
        ) : null}
        {showClock ? <Clock format={clockFormat} /> : null}
        {showStatus ? <SystemStatus /> : null}
      </div>
    </div>
  );
}

function DashboardSwitcher({
  title,
  dashboards,
}: {
  title: string;
  dashboards: DashboardOption[];
}) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const ref = useRef<HTMLDivElement>(null);
  useDismiss(open, () => setOpen(false), ref);

  // The dashboard being shown: the one whose route the address is under, else the one the title names.
  const current =
    dashboards.find((d) => pathname === `/${d.id}` || pathname.startsWith(`/${d.id}/`)) ??
    dashboards.find((d) => d.title === title);

  return (
    <div ref={ref} className={styles.switcher}>
      <button
        type="button"
        className={styles.switch}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        {current?.icon ? <Icon size={20}>{current.icon}</Icon> : null}
        <h1 className={styles.title}>{title}</h1>
        <Icon size={20}>
          <ChevronDown />
        </Icon>
      </button>
      {open ? (
        <ul className={styles.menu} role="listbox">
          {dashboards.map((d) => {
            const active = d.id === current?.id;
            return (
              <li key={d.id} role="none">
                <button
                  type="button"
                  className={styles.option}
                  role="option"
                  aria-selected={active}
                  // Already here: marked, and nothing to switch to.
                  disabled={active}
                  onClick={() => {
                    setOpen(false);
                    navigate(`/${d.id}`);
                  }}
                >
                  {d.icon ? <Icon size={16}>{d.icon}</Icon> : null}
                  <span className={styles.optionTitle}>{d.title}</span>
                  {active ? (
                    <Icon size={16}>
                      <Check />
                    </Icon>
                  ) : null}
                </button>
              </li>
            );
          })}
        </ul>
      ) : null}
    </div>
  );
}

type Health = 'ok' | 'pending' | 'down';

function linkHealth(link: LinkStatus): Health {
  return link === 'open' ? 'ok' : link === 'connecting' ? 'pending' : 'down';
}

function integrationHealth(status: ConnectionStatus): Health {
  return status === 'connected' ? 'ok' : status === 'connecting' ? 'pending' : 'down';
}

const WORST: Health[] = ['down', 'pending', 'ok'];

export interface SystemStatusProps {
  /** Overrides the live runtime link status — for demos (the gallery) rather than real dashboards. */
  link?: LinkStatus;

  /** Overrides the live per-integration statuses, keyed by integration id. */
  statuses?: Readonly<Record<string, ConnectionStatus>>;

  /** Start with the detail popover open. Default `false`. */
  defaultOpen?: boolean;
}

/** One dot summarising the runtime link and every integration — green when all are up, amber while
 * something connects, red when something is down; click for per-system detail. `TopBar` renders it
 * from live status, so it only needs the props above for demos. */
export function SystemStatus({
  link: linkOverride,
  statuses: statusesOverride,
  defaultOpen = false,
}: SystemStatusProps) {
  const liveLink = useConnectionStatus();
  const liveStatuses = useIntegrationStatuses();
  const link = linkOverride ?? liveLink;
  const statuses = statusesOverride ?? liveStatuses;
  const [open, setOpen] = useState(defaultOpen);
  const ref = useRef<HTMLDivElement>(null);

  useDismiss(open, () => setOpen(false), ref);

  const rows: { name: string; health: Health; label: string }[] = [
    {
      name: 'Runtime',
      health: linkHealth(link),
      label: link === 'open' ? 'Connected' : link === 'connecting' ? 'Connecting…' : 'Unreachable',
    },
    ...Object.entries(statuses).map(([id, status]) => ({
      name: id.toUpperCase(),
      health: integrationHealth(status),
      label:
        status === 'connected'
          ? 'Connected'
          : status === 'connecting'
            ? 'Connecting…'
            : status === 'error'
              ? 'Error'
              : 'Disconnected',
    })),
  ];

  const overall = WORST.find((h) => rows.some((row) => row.health === h)) ?? 'ok';
  const summary =
    overall === 'ok'
      ? 'All systems operational'
      : overall === 'pending'
        ? 'Connecting…'
        : `${rows.filter((row) => row.health === 'down').length} system(s) down`;

  return (
    <div ref={ref} className={styles.switcher} data-health={overall}>
      <PlainButton
        className={`${styles.dot} ${styles.health}`}
        data-health={overall}
        aria-label={summary}
        aria-expanded={open}
        title={summary}
        onClick={() => setOpen((o) => !o)}
      >
        <span className={styles.led} />
      </PlainButton>
      {open ? (
        <div className={`${styles.popover} ${styles.health}`} data-health={overall} role="status">
          <div className={styles.summary}>
            <span className={styles.summaryDot} />
            <span className={styles.summaryText}>{summary}</span>
          </div>
          {rows.map((row) => (
            <div
              key={row.name}
              className={`${styles.row} ${styles.health}`}
              data-health={row.health}
            >
              <span className={styles.rowName}>{row.name}</span>
              <span className={styles.rowState}>{row.label}</span>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  );
}

function StatusChip({ children }: { children: ReactNode }) {
  return <div className={styles.chip}>{children}</div>;
}

export interface WeatherChipProps {
  /** A weather entity, like `ha:weather.forecast_home`, or a plain temperature sensor. */
  entity: EntityRef;

  /** Tapping a weather entity's pill opens its forecast in a drawer. `false` builds none and the pill is only what it shows, which is lighter on a small display. Default `true`. */
  drawer?: boolean;
}

/** Current weather, as a pill: an icon for the sky and a rounded whole-degree reading. A `weather`
 * entity gives the real condition, today's high and low once its forecast has loaded, and opens the
 * forecast when tapped (if its source has one); a plain `sensor.*` (an outdoor temperature) gets a
 * fixed sun and no forecast, since a sensor says nothing about the sky. The top bar's weather pill,
 * for a dashboard that builds its own header instead of using `TopBar`. */
export function WeatherChip({ entity, drawer = true }: WeatherChipProps) {
  return useEntity(entity)?.kind === 'weather' ? (
    <WeatherPill entity={entity} drawer={drawer} />
  ) : (
    <SensorWeather entity={entity} />
  );
}

function WeatherPill({ entity, drawer }: { entity: EntityRef; drawer: boolean }) {
  const handle = useEntityHandle('weather', entity);
  const { status } = handle;
  const weather = handle.entity;
  const condition = weather?.condition ?? 'unknown';
  const look = CONDITION_LOOK[condition];
  const ready = status === 'ready' && weather?.temperature !== undefined;
  const forecastable = ready && (weather?.forecasts?.length ?? 0) > 0;

  // Today's high and low sit under the reading, once the forecast has said what they are.
  const daily = useWeatherForecast(
    weather?.forecasts?.includes('daily') ? entity : undefined,
    'daily',
  );

  const today =
    daily.result?.points.find(
      (point) => new Date(point.timestamp).toDateString() === new Date().toDateString(),
    ) ?? daily.result?.points[0];

  const range =
    today?.temperature !== undefined && today.low !== undefined
      ? `${Math.round(today.temperature)}° / ${Math.round(today.low)}°`
      : undefined;

  const chip = (
    <StatusChip>
      <span className={styles.skyIcon}>
        <WeatherIcon condition={condition} size={14} />
      </span>
      <span className={styles.reading}>
        {ready
          ? `${Math.round(weather.temperature ?? 0)}°`
          : statusLabels[status as Exclude<typeof status, 'ready'>]}
      </span>
      {ready && range ? <span className={styles.range}>{range}</span> : null}
    </StatusChip>
  );

  if (!forecastable || !drawer) {
    return chip;
  }

  return (
    <DrawerTrigger
      icon={look.icon}
      label={weather?.name ?? 'Weather'}
      kind="Weather"
      body={<WeatherForecast entity={entity} />}
    >
      {(open) => (
        <PlainButton aria-label={`${weather?.name ?? 'Weather'}: forecast`} onClick={open}>
          {chip}
        </PlainButton>
      )}
    </DrawerTrigger>
  );
}

function SensorWeather({ entity }: { entity: EntityRef }) {
  const handle = useEntityHandle('sensor', entity);
  const { status } = handle;
  const sensor = handle.entity;

  return (
    <StatusChip>
      <span className={styles.sun}>
        <Icon size={14}>
          <Sun />
        </Icon>
      </span>
      <span className={styles.plain}>
        {status === 'ready' && sensor?.numeric !== undefined
          ? `${Math.round(sensor.numeric)}°`
          : statusLabels[status as Exclude<typeof status, 'ready'>]}
      </span>
    </StatusChip>
  );
}

/** How long a scene's dot shows it has been pressed, from the press. */
const SCENE_DONE_MS = 1500;

function SceneDot({
  entity,
  icon,
  color,
}: {
  entity: EntityRef;
  icon?: ReactNode;
  color?: string;
}) {
  const handle = useEntityHandle('action', entity);
  const label = handle.entity?.name ?? '';
  // A press is answered at once, for a fixed time and whatever the scene does: the dot pops, turns green and
  // draws a check in place of its icon. It cannot be pressed again until that is over.
  const { active: done, start } = useTimed(SCENE_DONE_MS);

  return (
    <m.button
      type="button"
      className={styles.scene}
      {...(color ? { style: { '--scene': color } as CSSProperties } : {})}
      aria-label={label}
      title={label}
      disabled={done}
      onClick={() => {
        if (start()) {
          void handle.command('trigger');
        }
      }}
      animate={{ scale: done ? [1, 1.18, 1] : 1 }}
      transition={{ duration: 0.4 }}
    >
      <m.span
        className={styles.sceneIcon}
        animate={{ opacity: done ? 0 : 1, scale: done ? 0.5 : 1 }}
        transition={{ duration: 0.18 }}
      >
        <Icon size={18}>{icon ?? <Palette />}</Icon>
      </m.span>
      {done ? (
        <span className={styles.check}>
          <CheckMark />
        </span>
      ) : null}
    </m.button>
  );
}

function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  return words
    .slice(0, 2)
    .map((word) => word.charAt(0).toUpperCase())
    .join('');
}

function PresenceAvatar({ entity, colors }: { entity: EntityRef; colors: readonly string[] }) {
  const handle = useEntityHandle('person', entity);
  const person = handle.entity;
  const name = person?.name ?? '';
  const picture = person?.pictureUrl;
  // Someone who is out (or unreachable) reads as an inactive avatar: dimmed and without color.
  const active = person === undefined || (person.home && person.availability === 'ready');
  const where = person?.home ? 'home' : (person?.location ?? '');
  const label = name ? (where ? `${name}, ${where}` : name) : fallbackName(entity);

  return (
    <div
      className={styles.avatar}
      role="img"
      aria-label={label}
      title={label}
      data-active={active}
      data-picture={picture !== undefined}
      style={{ '--hs-avatar': colorForId(entity, colors) } as CSSProperties}
    >
      {picture ? (
        <img className={styles.picture} src={picture} alt="" />
      ) : (
        <span className={styles.initials}>{initials(name)}</span>
      )}
    </div>
  );
}

export interface PresenceStackProps {
  /** People, as refs like `ha:person.dan`: one avatar each (their picture, or a colored initial). Someone who is away is dimmed and grayscale. */
  entities: EntityRef[];

  /** Only colors the initial of people without a picture; each gets one, always the same. Optional: a built-in set of six is used by default. */
  colors?: readonly string[];
}

/** Overlapping avatar circles for a handful of `person.*` entities. */
export function PresenceStack({ entities, colors = DEFAULT_PRESENCE_COLORS }: PresenceStackProps) {
  return (
    <div className={styles.stack}>
      {entities.map((entity) => (
        <PresenceAvatar key={entity} entity={entity} colors={colors} />
      ))}
    </div>
  );
}

/** Locale-resolved by default; `format` forces 12/24h since browsers don't expose the OS setting. When a locale uses AM/PM, `formatToParts` lets it render separately, smaller. */
function formatClock(
  now: Date,
  format: 'auto' | '12h' | '24h',
): { time: string; dayPeriod: string | null } {
  const parts = new Intl.DateTimeFormat(undefined, {
    hour: format === '24h' ? '2-digit' : 'numeric',
    minute: '2-digit',
    ...(format === 'auto' ? {} : { hour12: format === '12h' }),
  }).formatToParts(now);

  const dayPeriod = parts.find((part) => part.type === 'dayPeriod')?.value ?? null;
  const time = parts
    .filter((part) => part.type !== 'dayPeriod')
    .map((part) => part.value)
    .join('')
    .trim();

  return { time, dayPeriod };
}

function useNow(intervalMs: number): Date {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);

  return now;
}

/** A short date pill (`Tue · Oct 6`), the top bar's, for a dashboard that builds its own header. */
export function DateChip() {
  const now = useNow(60_000);
  const weekday = now.toLocaleDateString(undefined, { weekday: 'short' });
  const day = now.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
  return (
    <StatusChip>
      <span className={styles.plain}>
        {weekday} · {day}
      </span>
    </StatusChip>
  );
}

export interface ClockProps {
  /** `'auto'` follows the browser's locale (browsers don't read the OS 12/24h setting, so an `en-US` browser shows AM/PM); `'12h'`/`'24h'` force it. Default `'auto'`. */
  format?: 'auto' | '12h' | '24h';
}

/** The top bar's clock: the time in the large clock type, with AM/PM beside it in a 12h format. For
 * a dashboard that builds its own header instead of using `TopBar`. */
export function Clock({ format = 'auto' }: ClockProps) {
  const now = useNow(30_000);
  const { time, dayPeriod } = formatClock(now, format);

  return (
    <div className={styles.clockGroup}>
      <span className={styles.clock}>{time}</span>
      {dayPeriod ? <span className={styles.period}>{dayPeriod}</span> : null}
    </div>
  );
}

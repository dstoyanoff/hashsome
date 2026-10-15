/** @jsxImportSource @emotion/react */
import type { ConnectionStatus, EntityRef, LinkStatus } from '@hashsome/core';
import { Box, Flex, Typography } from 'e-prim';
import { motion } from 'motion/react';
import { PlainButton } from '../layout/plain-button.tsx';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import type { IconName } from '../icon-data.ts';
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
import { TOP_ROW } from '../theme/grid.ts';
import { CheckMark } from '../layout/check-mark.tsx';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import { useTimed } from '../layout/use-timed.ts';
import { useWeatherForecast } from '../use-weather-forecast.ts';
import { SensorReadout } from './sensor-readout.tsx';
import { WeatherForecast } from './weather-forecast.tsx';
import { WeatherIcon } from './weather-icon.tsx';
import { CONDITION_LOOK } from './weather-look.ts';

/** One entry in the title's dashboard-switcher dropdown. */
export interface DashboardOption {
  /** Matches the dashboard route id — switching navigates to `/{id}`, always its root. */
  id: string;
  title: string;
  icon?: IconName;
}

export interface TopBarScene {
  entity: EntityRef;
  icon?: IconName;

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
    <Flex
      align="center"
      justify="space-between"
      gap={4}
      // A whole number of grid modules whatever is in it, so what is under it starts on the grid, and
      // no padding of its own, so its content is as far from the page's edge as the rest.
      css={{ minHeight: TOP_ROW }}
    >
      <Flex align="center" gap={3}>
        {dashboards && dashboards.length > 1 ? (
          <DashboardSwitcher title={title} dashboards={dashboards} />
        ) : (
          <Typography as="h1" variant="title">
            {title}
          </Typography>
        )}
        {scenes?.length ? (
          <Flex align="center" gap={2.5}>
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
          </Flex>
        ) : null}
      </Flex>
      <Flex align="center" gap={3}>
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
      </Flex>
    </Flex>
  );
}

/** The height of the switcher's button, and of each entry in its list. */
const SWITCHER_HEIGHT = 40;

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
    <Flex ref={ref} position="relative">
      <Flex
        as="button"
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        align="center"
        justify="center"
        gap={2}
        cursor="pointer"
        color="text"
        background="surface"
        border
        height={SWITCHER_HEIGHT}
        px={4}
        radius="chrome"
      >
        {current?.icon ? <Icon name={current.icon} size={20} /> : null}
        <Typography as="h1" variant="title">
          {title}
        </Typography>
        <Icon name="lu:chevron-down" size={20} />
      </Flex>
      {open ? (
        <Flex
          as="ul"
          role="listbox"
          direction="column"
          background="surfaceRaised"
          radius="row"
          shadow="drawer"
          p={1.5}
          gap={0.5}
          position="absolute"
          zIndex="dropdown"
          mt={2}
          minWidth="100%"
          css={{ top: '100%', left: 0, listStyle: 'none', whiteSpace: 'nowrap' }}
        >
          {dashboards.map((d) => {
            const active = d.id === current?.id;
            return (
              <li key={d.id} role="none">
                <Flex
                  as="button"
                  type="button"
                  role="option"
                  aria-selected={active}
                  // Already here: marked, and nothing to switch to.
                  disabled={active}
                  onClick={() => {
                    setOpen(false);
                    navigate(`/${d.id}`);
                  }}
                  align="center"
                  gap={2}
                  width="100%"
                  // As tall as the button that opened the list, so an entry is as easy to hit on a touch screen.
                  height={SWITCHER_HEIGHT}
                  radius="small"
                  px={3}
                  color="text"
                  background={active ? 'surface' : 'transparent'}
                  cursor={active ? 'default' : 'pointer'}
                  css={{ textAlign: 'left' }}
                >
                  {d.icon ? <Icon name={d.icon} size={16} /> : null}
                  <Typography as="span" variant="body" grow={1}>
                    {d.title}
                  </Typography>
                  {active ? <Icon name="lu:check" size={16} /> : null}
                </Flex>
              </li>
            );
          })}
        </Flex>
      ) : null}
    </Flex>
  );
}

type Health = 'ok' | 'pending' | 'down';

/** The palette color each health reads as. */
const HEALTH_COLORS = { ok: 'success', pending: 'warm', down: 'danger' } as const;

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
    <Flex ref={ref} position="relative">
      <PlainButton
        aria-label={summary}
        aria-expanded={open}
        title={summary}
        onClick={() => setOpen((o) => !o)}
        align="center"
        justify="center"
        width={32}
        height={32}
        radius="full"
      >
        <Flex
          as="span"
          width={12}
          height={12}
          radius="full"
          css={({ palette }) => ({
            background: palette[HEALTH_COLORS[overall]],
            boxShadow: `0 0 0 4px color-mix(in srgb, ${palette[HEALTH_COLORS[overall]]} 20%, transparent)`,
          })}
        />
      </PlainButton>
      {open ? (
        <Flex
          direction="column"
          gap={2}
          background="surfaceRaised"
          radius="row"
          shadow="drawer"
          p={3.5}
          role="status"
          position="absolute"
          zIndex="dropdown"
          mt={2}
          minWidth={220}
          css={{ top: '100%', right: 0, whiteSpace: 'nowrap' }}
        >
          <Flex align="center" gap={2}>
            <Flex
              as="span"
              width={8}
              height={8}
              radius="full"
              css={({ palette }) => ({ background: palette[HEALTH_COLORS[overall]] })}
            />
            <Typography as="span" variant="bodyStrong">
              {summary}
            </Typography>
          </Flex>
          {rows.map((row) => (
            <Flex key={row.name} align="center" justify="space-between" gap={6}>
              <Typography as="span" variant="body">
                {row.name}
              </Typography>
              <Typography
                as="span"
                variant="label"
                css={({ palette }) => ({ color: palette[HEALTH_COLORS[row.health]] })}
              >
                {row.label}
              </Typography>
            </Flex>
          ))}
        </Flex>
      ) : null}
    </Flex>
  );
}

function StatusChip({ children }: { children: ReactNode }) {
  return (
    <Flex align="center" gap={2} background="surface" border radius="full" px={3.5} py={2.5}>
      {children}
    </Flex>
  );
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

/** Text boxes cut to the height of the digits, so the reading and the range, in different sizes, are
 * both centred on their digits and not on the font's line box (which sits a little low or high).
 * Where the browser does not know `text-box` the text keeps its usual box. */
/** The height of one line of the 12px label text. */
const LABEL_LINE = 15;

const TRIM_TO_DIGITS = { textBox: 'trim-both cap alphabetic' } as const;

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
      {/* As tall as a line of the label, which the trimmed texts no longer are: the pill keeps the
          height of the bar's other pills. */}
      <Flex as="span" css={{ minHeight: LABEL_LINE }}>
        <WeatherIcon condition={condition} size={14} />
      </Flex>
      <Typography as="span" variant="label" css={TRIM_TO_DIGITS}>
        {ready
          ? `${Math.round(weather.temperature ?? 0)}°`
          : statusLabels[status as Exclude<typeof status, 'ready'>]}
      </Typography>
      {ready && range ? (
        <Typography as="span" variant="secondary" color="textMuted" css={TRIM_TO_DIGITS}>
          {range}
        </Typography>
      ) : null}
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
      <Flex as="span" css={({ palette }) => ({ color: palette.warm })}>
        <Icon name="lu:sun" size={14} />
      </Flex>
      <Typography as="span" variant="label">
        {status === 'ready' && sensor?.numeric !== undefined
          ? `${Math.round(sensor.numeric)}°`
          : statusLabels[status as Exclude<typeof status, 'ready'>]}
      </Typography>
    </StatusChip>
  );
}

/** How long a scene's dot shows it has been pressed, from the press. */
const SCENE_DONE_MS = 1500;

function SceneDot({ entity, icon, color }: { entity: EntityRef; icon?: IconName; color?: string }) {
  const handle = useEntityHandle('action', entity);
  const label = handle.entity?.name ?? '';
  // A press is answered at once, for a fixed time and whatever the scene does: the dot pops, turns green and
  // draws a check in place of its icon. It cannot be pressed again until that is over.
  const { active: done, start } = useTimed(SCENE_DONE_MS);

  return (
    <Flex
      as={motion.button}
      type="button"
      aria-label={label}
      title={label}
      disabled={done}
      onClick={() => {
        if (start()) {
          void handle.command('trigger');
        }
      }}
      align="center"
      justify="center"
      width={40}
      height={40}
      radius="full"
      position="relative"
      cursor={done ? 'default' : 'pointer'}
      animate={{ scale: done ? [1, 1.18, 1] : 1 }}
      transition={{ duration: 0.4 }}
      css={({ palette }) => ({
        background: done ? palette.success : (color ?? palette.accent),
        color: palette.accentText,
        transition: 'background-color 0.25s ease',
      })}
    >
      <Box
        as={motion.span}
        css={{ display: 'grid' }}
        animate={{ opacity: done ? 0 : 1, scale: done ? 0.5 : 1 }}
        transition={{ duration: 0.18 }}
      >
        <Icon name={icon ?? 'lu:palette'} size={18} />
      </Box>
      {done ? (
        <Flex align="center" justify="center" position="absolute" css={{ inset: 0 }}>
          <CheckMark />
        </Flex>
      ) : null}
    </Flex>
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
    <Flex
      role="img"
      aria-label={label}
      title={label}
      data-active={active}
      align="center"
      justify="center"
      color="accentText"
      radius="full"
      width={34}
      height={34}
      overflow="hidden"
      ml={-2.5}
      position="relative"
      css={({ palette }) => ({
        background: picture ? undefined : colorForId(entity, colors),
        ...(active
          ? {}
          : {
              // Faded by a light overlay on top of its own color, not by opacity: a see-through
              // avatar would show the one it overlaps underneath.
              '&::after': {
                content: '""',
                position: 'absolute',
                inset: 0,
                background: palette.surface,
                opacity: 0.6,
              },
            }),
      })}
    >
      {picture ? (
        <Box
          as="img"
          src={picture}
          alt=""
          width="100%"
          height="100%"
          css={{ objectFit: 'cover' }}
        />
      ) : (
        <Typography as="span" variant="bodyStrong">
          {initials(name)}
        </Typography>
      )}
    </Flex>
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
    <Flex align="center" pl={2.5}>
      {entities.map((entity) => (
        <PresenceAvatar key={entity} entity={entity} colors={colors} />
      ))}
    </Flex>
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
      <Typography as="span" variant="label">
        {weekday} · {day}
      </Typography>
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
    <Flex align="flex-start" gap={1}>
      <Typography as="span" variant="clock" css={{ lineHeight: `${TOP_ROW}px` }}>
        {time}
      </Typography>
      {dayPeriod ? (
        <Typography as="span" variant="label" css={{ textTransform: 'uppercase' }}>
          {dayPeriod}
        </Typography>
      ) : null}
    </Flex>
  );
}

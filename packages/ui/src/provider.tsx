/// <reference types="vite/client" />
import {
  RemoteClient,
  type Client,
  type EntityRef,
  type RemoteClientOptions,
} from '@hashsome/core';
import { motionFromAddress, setMotionLevel, type MotionLevel } from './motion/mode.ts';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react';
import {
  DebugContext,
  debugFromEnv,
  useDebugState,
  useFullscreenKept,
  type ThemeChoice,
} from './debug.ts';
import { DebugMenu } from './layout/debug-menu.tsx';
import { DetailProvider } from './layout/detail-provider.tsx';
import { EntityDrawer } from './layout/entity-drawer.tsx';
import { IdleReturnContext } from './layout/use-idle-return.ts';
import { themeCss } from './style/variables.ts';
import { useThemeStyle } from './style/use-theme-style.ts';
import { type Density } from './theme/density.ts';
import { DensityContext } from './theme/use-density.ts';
import { ThemeContext } from './theme/use-theme.ts';
import './style/base.css';
import './style/fonts.css';
import { applyThemeOverrides, densityTokens, type ThemeOverrides } from './theme/overrides.ts';
import { isDarkAt, msUntilSwitch, sunIsDown, type ThemeSchedule } from './theme/schedule.ts';
import { DEFAULT_FONT, darkTheme, lightTheme, withFontFamily } from './theme/index.ts';

const HashsomeContext = createContext<Client | null>(null);

export type { ThemeSchedule };

/** How much the app moves, all of it CSS the browser runs itself (no animation library): `reduced` none; `light` the cheap ones that play as something appears (a page or a drawer fading and sliding in, a button's squeeze, swatches popping in, a color changing); `full` those and more (things leaving, lists staggering in, text rolling, numbers moving, gentle pulses); `auto` (the default) is `full`. */
export type MotionPreference = 'auto' | 'full' | 'light' | 'reduced';

/** `'system'` follows the display's own light/dark preference; a `ThemeSchedule` changes with the time of day. */
export type ThemeMode = 'light' | 'dark' | 'system' | ThemeSchedule;

export interface HashsomeProviderProps {
  /** Runtime WebSocket URL. Defaults to `/ws` on the current origin. */
  url?: string;

  /** Provide a ready-made client (gallery, tests). */
  client?: Client;

  /** Options for the default `RemoteClient` (reconnect timing, a custom socket factory). Ignored when `client` is given. */
  clientOptions?: Omit<RemoteClientOptions, 'url'>;

  /** `'light'`, `'dark'`, `'system'` (follows the browser/OS preference and updates live), or a schedule: `{ dark: { from: '19:00', to: '07:00' } }` is dark between those times on the display's clock, `{ sun: 'ha:sun.sun' }` is dark while that entity, a `daylight` sensor (`on` while the sun is up; the Home Assistant integration makes one of `sun.sun`), is `off`. Default `'dark'`. A schedule and the document shell's first paint use `'system'` until the time or the entity is known. A `useThemeToggle()` caller (a toggle of the project's own) can still override this at runtime. Pass a constant defined outside the component. */
  theme?: ThemeMode;

  /** The font family applied everywhere. `'Inter'` (the default) is bundled with the app; any other family has to be loaded by the project itself, from its own files (see the README's "Fonts"): nothing is fetched from another host. */
  font?: string;

  /** `'compact'` for small square displays: tighter spacing, shorter tiles, smaller icon circles. Default `'comfortable'`. */
  density?: Density;

  /** How much the app moves (see `MotionPreference`): `'reduced'` none, for a slow display; `'light'` the cheap animations, which play as something appears; `'full'` (and `'auto'`, the default) all of them. A device can choose for itself with `?motion=reduced` (or `light`, or `full`) on the address it opens, which wins over this prop. Nothing is kept: it holds while the app is open, moving between its pages, and a reload without the parameter goes back to this prop. */
  motion?: MotionPreference;

  /** Partial changes to the built-in theme — colors per light/dark, radii, typography, spacing, density sizes. Pass a constant defined outside the component: a new object each render rebuilds the theme each render. */
  overrides?: ThemeOverrides;

  /** Shows the debug menu: a floating button at the bottom left whose popover shows the module grid, makes the page fullscreen and changes the theme, on the device, kept there. Default: on when `HASHSOME_DEBUG=1` (or `true`) was in the environment of `hashsome dev` or `build`. */
  debug?: boolean;

  /** The daylight sensor (`on` while the sun is up) the debug menu's Sun theme follows, when `theme` is not a sun schedule already (`theme={{ sun: … }}` names one). Without either, the menu has no Sun choice: Hashsome does not know which entity is the sun. */
  sun?: EntityRef;

  /** How long, in ms, a display is left alone on a dashboard's page other than its main one before it goes back to the main page, so a wall display does not stay on the music page for hours. Any touch, click, key or scroll starts the time again, and the main page itself is left alone. It is done by the dashboard's `NavRail`, `NavDock` or `NavBar`, which can set their own `idleReturn` (a time, or `false`) over this. Default `false`: off. */
  idleReturn?: number | false;

  /** The app. */
  children: ReactNode;
}

function defaultUrl(): string {
  const { protocol, host } = window.location;
  return `${protocol === 'https:' ? 'wss:' : 'ws:'}//${host}/ws`;
}

// jsdom (unit tests) has no `matchMedia` — guarded so only `theme="system"` ever touches it, and
// even then it degrades to `false` instead of throwing if a test environment lacks it too.
function systemPrefersDark(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-color-scheme: dark)').matches
  );
}

export interface ThemeModeState {
  /** The theme actually in effect right now — never `'system'`, already resolved against the
   * live OS preference and any `useThemeToggle()` override. */
  resolved: 'light' | 'dark';

  /** Flips between light and dark, overriding the configured `theme` (including `'system'`) until
   * the page reloads. */
  toggle: () => void;
}

const ThemeModeContext = createContext<ThemeModeState | null>(null);

/** Reads the live light/dark state and a way to flip it, for a toggle of the project's own (the debug menu has a Theme choice).
 * Must be rendered inside `<HashsomeProvider>`. */
export function useThemeToggle(): ThemeModeState {
  const value = useContext(ThemeModeContext);
  if (!value) {
    throw new Error('useThemeToggle() must be used inside <HashsomeProvider>');
  }

  return value;
}

/** Settles motion once, when the app starts, before anything renders: a flag on the page says how much the stylesheets animate,
 * and `reduced` switches off every CSS transition and animation. */
function useMotionMode(preference: MotionPreference): MotionLevel {
  const [mode] = useState<MotionLevel>(() => {
    const chosen = motionFromAddress() ?? preference;
    // The level is a flag on the page that the stylesheets go by (`html[data-motion='light']`), and the few things done in JavaScript.
    const level: MotionLevel = chosen === 'reduced' || chosen === 'light' ? chosen : 'full';
    setMotionLevel(level);
    if (typeof document !== 'undefined') {
      document.documentElement.dataset.motion = level;
    }

    return level;
  });

  return mode;
}

const SUN_KEY = 'hashsome:sun-down';

/** The last answer a sun entity gave, kept across page loads so a reload at night does not start light. */
function rememberedSun(): boolean | undefined {
  try {
    const stored = localStorage.getItem(SUN_KEY);
    return stored === null ? undefined : stored === '1';
  } catch {
    return undefined;
  }
}

/** The sun entity a theme follows, if it is a sun schedule. */
function sunOf(mode: ThemeMode): EntityRef | undefined {
  return typeof mode === 'object' && 'sun' in mode ? mode.sun : undefined;
}

/** The theme in effect: what the debug menu chose, if it chose, else what the project configured.
 * The sun is the project's: the one its theme follows, or the `sun` it gave. Without one there is no
 * sun to follow, so that choice (kept from a visit that had one) leaves the configured theme as it was. */
function chosenTheme(
  configured: ThemeMode,
  choice: ThemeChoice | null,
  sun: EntityRef | undefined,
): ThemeMode {
  if (choice === null) {
    return configured;
  }

  if (choice === 'sun') {
    return sun === undefined ? configured : { sun };
  }

  return choice;
}

/** Resolves the configured mode: `'system'` against the live OS/browser preference (updating if it
 * changes while open — a kiosk tablet left running overnight should follow a scheduled OS-level dark
 * mode, for example), a time range against the clock (re-checked at each boundary and whenever the
 * page is shown again, since a sleeping tablet misses timers) and a sun entity against what it says.
 * `override` (set via `useThemeToggle().toggle()`) wins over all of them until the page reloads. */
function useThemeMode(mode: ThemeMode, client: Client): ThemeModeState {
  const range = typeof mode === 'object' && 'dark' in mode ? mode.dark : undefined;
  const from = range?.from;
  const to = range?.to;
  const sunRef = typeof mode === 'object' && 'sun' in mode ? mode.sun : undefined;
  const followsSystem = mode === 'system' || sunRef !== undefined;

  const [systemDark, setSystemDark] = useState(() => followsSystem && systemPrefersDark());
  const [override, setOverride] = useState<'light' | 'dark' | null>(null);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    if (!followsSystem || typeof window.matchMedia !== 'function') {
      return;
    }

    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystemDark(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, [followsSystem]);

  useEffect(() => {
    if (from === undefined || to === undefined) {
      return;
    }

    const bounds = { from, to };
    let timer: ReturnType<typeof setTimeout> | undefined;
    const arm = () => {
      clearTimeout(timer);
      const wait = msUntilSwitch(bounds, new Date());
      if (Number.isFinite(wait)) {
        // A moment past the boundary, so the clock has certainly crossed it.
        timer = setTimeout(refresh, wait + 500);
      }
    };

    const refresh = () => {
      setNow(new Date());
      arm();
    };

    const onVisible = () => document.visibilityState === 'visible' && refresh();
    refresh();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [from, to]);

  // Memoized, like every other `subscribe` given to `useSyncExternalStore` (see hooks.ts): a new
  // function each render makes React resubscribe, which against a remote runtime is an unsubscribe and
  // a subscribe whose reply is a new entity object, which renders again, without end.
  const subscribeSun = useCallback(
    (onChange: () => void) => (sunRef ? client.subscribe(sunRef, onChange) : () => undefined),
    [client, sunRef],
  );

  const readSun = useCallback(
    () => (sunRef ? client.getEntity(sunRef) : undefined),
    [client, sunRef],
  );

  const sun = useSyncExternalStore(subscribeSun, readSun, () => undefined);

  const sunDown = sunIsDown(sun);
  useEffect(() => {
    if (sunDown === undefined) {
      return;
    }

    try {
      localStorage.setItem(SUN_KEY, sunDown ? '1' : '0');
    } catch {
      // Without storage the next load just starts from the system preference again.
    }
  }, [sunDown]);

  let configured: 'light' | 'dark';
  if (mode === 'light' || mode === 'dark') {
    configured = mode;
  } else if (range) {
    configured = isDarkAt(range, now) ? 'dark' : 'light';
  } else if (sunRef) {
    configured = (sunDown ?? rememberedSun() ?? systemDark) ? 'dark' : 'light';
  } else {
    configured = systemDark ? 'dark' : 'light';
  }

  const resolved = override ?? configured;

  return { resolved, toggle: () => setOverride(resolved === 'dark' ? 'light' : 'dark') };
}

/** Connects the tree to the runtime proxy. Render only on the client. */
export function HashsomeProvider({
  url,
  client,
  clientOptions,
  theme = 'dark',
  motion = 'auto',
  font = DEFAULT_FONT,
  density = 'comfortable',
  overrides,
  debug = debugFromEnv(),
  sun,
  idleReturn = false,
  children,
}: HashsomeProviderProps) {
  const instance = useMemo<Client>(
    () => client ?? new RemoteClient({ url: url ?? defaultUrl(), ...clientOptions }),
    [client, url, clientOptions],
  );

  useMotionMode(motion);
  const debugState = useDebugState();
  useFullscreenKept(debugState.fullscreen, debugState.setFullscreen);
  // The debug menu can choose a theme over the configured one; the sun is the project's own entity.
  const sunEntity = sunOf(theme) ?? sun;
  const themeMode = useThemeMode(
    chosenTheme(theme, debug ? debugState.themeChoice : null, sunEntity),
    instance,
  );

  const resolvedTheme = useMemo(
    () =>
      withFontFamily(
        applyThemeOverrides(
          themeMode.resolved === 'dark' ? darkTheme : lightTheme,
          themeMode.resolved,
          overrides,
        ),
        font,
      ),
    [themeMode.resolved, font, overrides],
  );

  const densityValue = useMemo(() => densityTokens(density, overrides), [density, overrides]);
  useThemeStyle(
    useMemo(() => themeCss(resolvedTheme, densityValue), [resolvedTheme, densityValue]),
  );

  useEffect(() => {
    instance.connect();
    return () => instance.close();
  }, [instance]);

  return (
    <HashsomeContext.Provider value={instance}>
      <ThemeModeContext.Provider value={themeMode}>
        <DensityContext.Provider value={densityValue}>
          <ThemeContext.Provider value={resolvedTheme}>
            <DebugContext.Provider value={debugState}>
              <IdleReturnContext.Provider value={idleReturn}>
                <DetailProvider>
                  {children}
                  <EntityDrawer />
                  {debug ? <DebugMenu configured={theme} sun={sunEntity} /> : null}
                </DetailProvider>
              </IdleReturnContext.Provider>
            </DebugContext.Provider>
          </ThemeContext.Provider>
        </DensityContext.Provider>
      </ThemeModeContext.Provider>
    </HashsomeContext.Provider>
  );
}

export function useClient(): Client {
  const client = useContext(HashsomeContext);
  if (!client) {
    throw new Error('Hashsome hooks must be used inside <HashsomeProvider>');
  }

  return client;
}

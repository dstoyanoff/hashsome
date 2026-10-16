import {
  RemoteClient,
  type Client,
  type EntityRef,
  type RemoteClientOptions,
} from '@hashsome/core';
import { Global, ThemeProvider as EmotionThemeProvider, type Theme } from '@emotion/react';
import { ThemeProvider } from 'e-prim';
import { MotionGlobalConfig } from 'motion/react';
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
import { UNITS_PER_SPACE, type Density } from './theme/density.ts';
import { globalStyles } from './theme/global-styles.ts';
import { applyThemeOverrides, densityTokens, type ThemeOverrides } from './theme/overrides.ts';
import { isDarkAt, msUntilSwitch, sunIsDown, type ThemeSchedule } from './theme/schedule.ts';
import {
  DEFAULT_FONT,
  darkTheme,
  googleFontHref,
  lightTheme,
  withFontFamily,
} from './theme/index.ts';

const HashsomeContext = createContext<Client | null>(null);

export type { ThemeSchedule };

/** `reduced` turns every animation off, for a slow display; `full` keeps them; `auto` (the default) leaves things as they are. */
export type MotionPreference = 'auto' | 'full' | 'reduced';

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

  /** Any Google Fonts family name (e.g. `'Inter'`, `'Roboto'`, `'Poppins'`), loaded dynamically. Default `'Inter'`. */
  font?: string;

  /** `'compact'` for small square displays: tighter spacing, shorter tiles, smaller icon circles. Default `'comfortable'`. */
  density?: Density;

  /** `'reduced'` turns animations and transitions off, for a slow display (and draws what would animate as plain elements, which is most of what such a display saves), `'full'` keeps them and `'auto'` (the default) leaves things as they are. A device can choose for itself with `?motion=reduced` (or `full`) on the address it opens, which wins over this prop. Nothing is kept: it holds while the app is open, moving between its pages, and a reload without the parameter goes back to this prop. */
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

/** Loads a Google Fonts family at runtime via a single `<link>` this hook owns and reuses (keyed
 * by a `data-hashsome-font` marker, not the family, so switching fonts updates it in place instead of
 * accumulating stale `<link>` tags). A no-op outside the browser (SSR has no `document`). */
function useGoogleFont(family: string) {
  useEffect(() => {
    if (typeof document === 'undefined') {
      return;
    }

    let link = document.querySelector<HTMLLinkElement>('link[data-hashsome-font]');
    if (!link) {
      link = document.createElement('link');
      link.rel = 'stylesheet';
      link.dataset.hashsomeFont = '';
      document.head.appendChild(link);
    }

    link.href = googleFontHref(family);
  }, [family]);
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

/** What the address asks for motion: `?motion=reduced` or `?motion=full`. Nothing is kept, and the
 * app reads it once, when it starts, so moving between pages inside the app keeps it and a reload
 * without it does not. `?motion=auto` (or anything else) leaves it to the prop. */
function motionFromAddress(): 'full' | 'reduced' | undefined {
  try {
    const asked = new URLSearchParams(window.location.search).get('motion');
    return asked === 'reduced' || asked === 'full' ? asked : undefined;
  } catch {
    return undefined;
  }
}

/** Settles motion once, when the app starts, before anything renders: in `reduced` every animation
 * motion would run is skipped, and a flag on the page lets the global styles switch off CSS
 * transitions and animations too. */
function useMotionMode(preference: MotionPreference): MotionPreference {
  const [mode] = useState<MotionPreference>(() => {
    const chosen = motionFromAddress() ?? preference;
    MotionGlobalConfig.skipAnimations = chosen === 'reduced';
    if (typeof document !== 'undefined') {
      if (chosen === 'reduced') {
        document.documentElement.dataset.motion = 'reduced';
      } else {
        delete document.documentElement.dataset.motion;
      }
    }

    return chosen;
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

  // Memoized: Emotion recomputes the merged theme (and every `css` prop) when the function changes.
  const withDensity = useMemo(
    () =>
      (outer: Theme): Theme => ({ ...outer, density: densityTokens(density, overrides) }),
    [density, overrides],
  );

  useGoogleFont(font);
  const resolvedTheme = useMemo(
    () => ({
      ...withFontFamily(
        applyThemeOverrides(
          themeMode.resolved === 'dark' ? darkTheme : lightTheme,
          themeMode.resolved,
          overrides,
        ),
        font,
      ),
      // The spacing unit follows density, so `gap={3}` is always one space and the rest scale.
      spacing: densityTokens(density, overrides).space / UNITS_PER_SPACE,
    }),
    [themeMode.resolved, font, overrides, density],
  );

  useEffect(() => {
    instance.connect();
    return () => instance.close();
  }, [instance]);

  return (
    <HashsomeContext.Provider value={instance}>
      <ThemeModeContext.Provider value={themeMode}>
        <ThemeProvider theme={resolvedTheme}>
          <EmotionThemeProvider theme={withDensity}>
            <Global styles={globalStyles} />
            <DebugContext.Provider value={debugState}>
              <IdleReturnContext.Provider value={idleReturn}>
                <DetailProvider>
                  {children}
                  <EntityDrawer />
                  {debug ? <DebugMenu configured={theme} sun={sunEntity} /> : null}
                </DetailProvider>
              </IdleReturnContext.Provider>
            </DebugContext.Provider>
          </EmotionThemeProvider>
        </ThemeProvider>
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

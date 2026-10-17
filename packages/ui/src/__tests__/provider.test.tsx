import { LocalClient, MockIntegration, mockSensor } from '@hashsome/core';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useTheme } from '@emotion/react';
import { Box, Flex } from '../style/prim.tsx';
import { MotionGlobalConfig } from 'motion/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { computed } from '../test-utils.tsx';
import { HashsomeProvider, useThemeToggle, type ThemeMode } from '../provider.tsx';

afterEach(cleanup);

function Probe() {
  return <Box background="bg" data-testid="probe" />;
}

function renderWithTheme(theme?: ThemeMode) {
  const client = new LocalClient([new MockIntegration({})]);
  render(
    <HashsomeProvider client={client} {...(theme ? { theme } : {})}>
      <Probe />
    </HashsomeProvider>,
  );

  return computed(screen.getByTestId('probe'), 'background');
}

test('defaults to the dark theme when no theme prop is given', () => {
  expect(renderWithTheme()).toBe('#0B0A0D'); // darkTheme.palette.bg
});

test('theme="dark" resolves to the dark palette', () => {
  expect(renderWithTheme('dark')).toBe('#0B0A0D');
});

test('theme="light" resolves to the light palette', () => {
  expect(renderWithTheme('light')).toBe('#F4F1EB'); // lightTheme.palette.bg
});

test('theme="system" degrades to light instead of throwing when matchMedia is unavailable', () => {
  // jsdom has no matchMedia at all; this just confirms the guard in provider.tsx doesn't crash —
  // a real browser would resolve this against the live OS preference instead.
  expect(renderWithTheme('system')).toBe('#F4F1EB');
});

function ToggleProbe() {
  const { resolved, toggle } = useThemeToggle();
  return (
    <>
      <Box background="bg" data-testid="probe" />
      <button onClick={toggle}>{resolved}</button>
    </>
  );
}

test('useThemeToggle() flips the resolved theme, overriding the configured one', () => {
  const client = new LocalClient([new MockIntegration({})]);
  render(
    <HashsomeProvider client={client} theme="dark">
      <ToggleProbe />
    </HashsomeProvider>,
  );

  expect(computed(screen.getByTestId('probe'), 'background')).toBe('#0B0A0D');
  fireEvent.click(screen.getByRole('button', { name: 'dark' }));
  expect(computed(screen.getByTestId('probe'), 'background')).toBe('#F4F1EB');
  expect(screen.getByRole('button', { name: 'light' })).toBeTruthy();
});

function mount(props: { theme?: ThemeMode; density?: 'comfortable' | 'compact' } = {}) {
  const client = new LocalClient([new MockIntegration({})]);
  return render(
    <HashsomeProvider client={client} {...props}>
      <Probe />
    </HashsomeProvider>,
  );
}

test('the theme is installed globally on the page, not per dashboard', () => {
  mount({ theme: 'light' });
  expect(getComputedStyle(document.body).backgroundColor).toBe('rgb(244, 241, 235)');
});

function DensityProbe() {
  const { density } = useTheme();
  return <output data-testid="density">{JSON.stringify(density)}</output>;
}

test('density is a HashsomeProvider setting: compact tightens the spacing tokens in the theme', () => {
  const read = (density?: 'comfortable' | 'compact') => {
    const client = new LocalClient([new MockIntegration({})]);
    render(
      <HashsomeProvider client={client} {...(density ? { density } : {})}>
        <DensityProbe />
      </HashsomeProvider>,
    );

    const tokens = JSON.parse(screen.getByTestId('density').textContent ?? '{}') as {
      space: number;
      tileHeight: number;
    };

    cleanup();
    return tokens;
  };

  expect(read().space).toBe(12);
  expect(read('compact')).toMatchObject({ space: 8, tileHeight: 52 });
});

function ThemeProbe() {
  const { palette, radius, typography, density } = useTheme();
  return (
    <output data-testid="theme">
      {JSON.stringify({
        accent: palette.accent,
        text: palette.text,
        card: radius.card,
        small: radius.small,
        label: typography.label,
        space: density.space,
        tileHeight: density.tileHeight,
      })}
    </output>
  );
}

const themeOf = (props: Partial<React.ComponentProps<typeof HashsomeProvider>>) => {
  const client = new LocalClient([new MockIntegration({})]);
  render(
    <HashsomeProvider client={client} {...props}>
      <ThemeProbe />
    </HashsomeProvider>,
  );

  const value = JSON.parse(screen.getByTestId('theme').textContent ?? '{}') as Record<
    string,
    unknown
  >;

  cleanup();
  return value;
};

test('overrides change only the tokens they name, and light and dark are separate', () => {
  const overrides = {
    light: { palette: { accent: '#2d6cdf' } },
    shared: { radius: { card: '10px' }, typography: { label: { fontSize: 15 } } },
  };

  const light = themeOf({ theme: 'light', overrides });
  expect(light).toMatchObject({ accent: '#2d6cdf', text: '#211E26', card: '10px', small: '9px' });
  expect(light.label).toMatchObject({ fontSize: 15, fontWeight: 500 });

  const dark = themeOf({ theme: 'dark', overrides });
  expect(dark).toMatchObject({ accent: '#B85C38', card: '10px' });
});

test('without overrides the built-in theme is untouched', () => {
  expect(themeOf({ theme: 'dark' })).toMatchObject({ accent: '#B85C38', card: '25px', space: 12 });
});

test('density overrides apply to their density only', () => {
  const overrides = { density: { comfortable: { space: 16 }, compact: { tileHeight: 40 } } };
  expect(themeOf({ overrides })).toMatchObject({ space: 16, tileHeight: 68 });
  expect(themeOf({ density: 'compact', overrides })).toMatchObject({ space: 8, tileHeight: 40 });
});

function SpacingProbe() {
  const { spacing } = useTheme();
  return (
    <>
      <output data-testid="space">{spacing(3)}</output>
      <Flex data-testid="gap" gap={3} />
      <Flex data-testid="pad" p={6} />
    </>
  );
}

const spacingOf = (props: Partial<React.ComponentProps<typeof HashsomeProvider>>) => {
  const client = new LocalClient([new MockIntegration({})]);
  render(
    <HashsomeProvider client={client} {...props}>
      <SpacingProbe />
    </HashsomeProvider>,
  );

  const result = {
    unit: screen.getByTestId('space').textContent,
    gap: computed(screen.getByTestId('gap'), 'gap'),
    pad: computed(screen.getByTestId('pad'), 'padding'),
  };

  cleanup();
  return result;
};

test('the spacing unit follows density, so gap={3} is one space and other spacing scales with it', () => {
  expect(spacingOf({})).toEqual({ unit: '12px', gap: '12px', pad: '24px' });
  expect(spacingOf({ density: 'compact' })).toEqual({ unit: '8px', gap: '8px', pad: '16px' });
});

test('changing the density’s space rescales all spacing', () => {
  const overrides = { density: { comfortable: { space: 18 } } };
  expect(spacingOf({ overrides })).toEqual({ unit: '18px', gap: '18px', pad: '36px' });
  // The other density is untouched.
  expect(spacingOf({ density: 'compact', overrides }).gap).toBe('8px');
});

// A theme that follows the time of day.

const DARK_BG = '#0B0A0D';
const LIGHT_BG = '#F4F1EB';
const bgOf = () => computed(screen.getByTestId('probe'), 'background');
const FAKE = ['Date', 'setTimeout', 'clearTimeout'] as const;

function mountScheduled(theme: ThemeMode, integration = new MockIntegration({})) {
  const client = new LocalClient([integration]);
  render(
    <HashsomeProvider client={client} theme={theme}>
      <Probe />
    </HashsomeProvider>,
  );

  return integration;
}

test('a clock schedule is dark inside its hours and light outside them', () => {
  vi.useFakeTimers({ now: new Date(2026, 9, 6, 22, 0), toFake: [...FAKE] });
  try {
    mountScheduled({ dark: { from: '19:00', to: '07:00' } });
    expect(bgOf()).toBe(DARK_BG);
    cleanup();

    vi.setSystemTime(new Date(2026, 9, 6, 12, 0));
    mountScheduled({ dark: { from: '19:00', to: '07:00' } });
    expect(bgOf()).toBe(LIGHT_BG);
  } finally {
    vi.useRealTimers();
  }
});

test('a clock schedule switches by itself when its time comes, without a reload', () => {
  vi.useFakeTimers({ now: new Date(2026, 9, 6, 18, 59, 30), toFake: [...FAKE] });
  try {
    mountScheduled({ dark: { from: '19:00', to: '07:00' } });
    expect(bgOf()).toBe(LIGHT_BG);

    act(() => void vi.advanceTimersByTime(31_000));
    expect(bgOf()).toBe(DARK_BG);
  } finally {
    vi.useRealTimers();
  }
});

test('a clock schedule with a time that is not HH:MM is refused', () => {
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  expect(() => mountScheduled({ dark: { from: '7pm', to: '07:00' } })).toThrow('theme.dark.from');
  vi.restoreAllMocks();
});

beforeEach(() => localStorage.clear());

// The generic form of a sun: a daylight sensor, on while the sun is up.
const sunWith = (value: 'on' | 'off') =>
  new MockIntegration({
    entities: { 'sun.sun': mockSensor({ name: 'Sun', value, measurement: 'daylight' }) },
  });

test('a sun schedule is dark while the sun entity says it is below the horizon', async () => {
  mountScheduled({ sun: 'ha:sun.sun' }, sunWith('off'));
  await waitFor(() => expect(bgOf()).toBe(DARK_BG));
});

test('a sun schedule is light while the sun is up, and follows the entity when it sets', async () => {
  const sun = sunWith('on');
  mountScheduled({ sun: 'ha:sun.sun' }, sun);
  await waitFor(() => expect(bgOf()).toBe(LIGHT_BG));

  act(() => sun.update('sun.sun', { value: 'off' }));
  await waitFor(() => expect(bgOf()).toBe(DARK_BG));
});

test('a sun schedule starts from what the sun last said, until the entity is known', () => {
  localStorage.setItem('hashsome:sun-down', '1');
  // No such entity: nothing to go by yet, so the remembered night is what shows.
  mountScheduled({ sun: 'ha:sun.sun' }, new MockIntegration({}));
  expect(bgOf()).toBe(DARK_BG);
});

const SUN = { sun: 'ha:sun.sun' } as const;

test('a sun schedule subscribes to the sun once, however often the provider renders again', async () => {
  // Against a remote runtime a resubscribe is an unsubscribe and a subscribe whose reply is a new entity
  // object, which renders again and resubscribes, without end: it must stay at one.
  const client = new LocalClient([sunWith('on')]);
  const subscribe = vi.spyOn(client, 'subscribe');
  // A new element each time: the same one would be skipped, not rendered again.
  const tree = () => (
    <HashsomeProvider client={client} theme={SUN}>
      <Probe />
    </HashsomeProvider>
  );

  const view = render(tree());
  await waitFor(() => expect(bgOf()).toBe(LIGHT_BG));
  for (let i = 0; i < 4; i += 1) {
    view.rerender(tree());
  }

  expect(subscribe.mock.calls.filter(([ref]) => ref === 'ha:sun.sun')).toHaveLength(1);
});

// Motion: one device can ask for no animations with ?motion=reduced.

const resetMotion = () => {
  window.history.replaceState({}, '', '/');
  localStorage.clear();
  delete document.documentElement.dataset.motion;
  MotionGlobalConfig.skipAnimations = false;
};

const startAt = (search: string, props: { motion?: 'auto' | 'full' | 'reduced' } = {}) => {
  window.history.replaceState({}, '', `/bathroom${search}`);
  const client = new LocalClient([new MockIntegration({})]);
  render(
    <HashsomeProvider client={client} {...props}>
      <Probe />
    </HashsomeProvider>,
  );
};

const reduced = () => document.documentElement.dataset.motion === 'reduced';

test('?motion=reduced skips animations for that visit only, and nothing is kept', () => {
  resetMotion();
  startAt('?motion=reduced');
  expect(reduced()).toBe(true);
  expect(MotionGlobalConfig.skipAnimations).toBe(true);
  expect(localStorage.length).toBe(0);

  // A later visit with no query starts from the prop again.
  cleanup();
  delete document.documentElement.dataset.motion;
  MotionGlobalConfig.skipAnimations = false;
  startAt('');
  expect(reduced()).toBe(false);
  expect(MotionGlobalConfig.skipAnimations).toBe(false);
  resetMotion();
});

test('?motion=full overrides a reduced prop, and ?motion=auto leaves it to the prop', () => {
  resetMotion();
  startAt('', { motion: 'reduced' });
  expect(reduced()).toBe(true);
  cleanup();

  startAt('?motion=full', { motion: 'reduced' });
  expect(reduced()).toBe(false);
  expect(MotionGlobalConfig.skipAnimations).toBe(false);
  expect(localStorage.length).toBe(0);
  cleanup();

  startAt('?motion=auto', { motion: 'reduced' });
  expect(reduced()).toBe(true);
  resetMotion();
});

test('the default is to leave animations alone, and the prop can ask for none', () => {
  resetMotion();
  startAt('');
  expect(reduced()).toBe(false);
  expect(MotionGlobalConfig.skipAnimations).toBe(false);
  cleanup();

  startAt('', { motion: 'reduced' });
  expect(reduced()).toBe(true);
  resetMotion();
});

test('reduced motion switches off CSS transitions and animations, except the marked ones', () => {
  resetMotion();
  startAt('?motion=reduced');
  const css = [...document.querySelectorAll('style')].map((style) => style.textContent).join('\n');
  expect(css).toContain('html[data-motion="reduced"]');
  expect(css).toContain(':not([data-keep-motion])');
  expect(css).toMatch(/transition:none\s*!important/);
  resetMotion();
});

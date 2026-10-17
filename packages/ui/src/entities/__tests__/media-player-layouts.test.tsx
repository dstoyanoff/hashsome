import { mockLibrary, mockMediaPlayer, type EntityRef } from '@hashsome/core';
import { act, cleanup, fireEvent, screen } from '@testing-library/react';
import type { ComponentProps } from 'react';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { MediaPlayerBar } from '../media-player-bar.tsx';
import { MediaPlayerColumn } from '../media-player-column.tsx';
import { useEntityHandle } from '../../hooks.ts';
import { MediaBrowser } from '../media-browser.tsx';
import { MediaPlayerFull } from '../media-player-full.tsx';
import { MediaQueue } from '../media-queue.tsx';

/** What a dashboard's music page is: the widget in a surface that fills the space, with the player's
 * library below it when it has one. */
function RoomPage({ entity, name }: { entity: EntityRef; name?: string }) {
  const handle = useEntityHandle('mediaPlayer', entity);
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        flexGrow: 1,
        minHeight: 0,
        overflow: 'auto',
      }}
    >
      <MediaPlayerFull
        entity={entity}
        wide
        {...(name !== undefined ? { name } : {})}
        {...(handle.entity?.capabilities.browse === true
          ? { browser: <MediaBrowser entity={entity} layout="theater" /> }
          : {})}
      />
    </div>
  );
}

const now = () => new Date().toISOString();
const playing = (capabilities = {}) => ({
  room: mockMediaPlayer({
    name: 'Room',
    playback: 'playing',
    media: { title: 'Dreams', artist: 'Fleetwood Mac', album: 'Rumours' },
    volume: 0.4,
    position: 30,
    duration: 120,
    positionUpdatedAt: now(),
    capabilities: { browse: true, search: true, seek: true, ...capabilities },
  }),
});

const library = { library: mockLibrary() };

test('the column shows what is playing, a seekable progress bar and an always-visible volume bar', () => {
  const { ha } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
  expect(screen.getByRole('heading', { name: 'Dreams' })).toBeTruthy();
  expect(screen.getByText('Fleetwood Mac · Rumours')).toBeTruthy();
  const volume = screen.getByRole('slider', { name: 'Volume level' });
  expect(volume.getAttribute('aria-valuenow')).toBe('40');
  const position = screen.getByRole('slider', { name: 'Position' });
  fireEvent.keyDown(position, { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toMatchObject({
    command: 'seek',
    args: { position: expect.any(Number) },
  });

  fireEvent.keyDown(volume, { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setVolume', args: { volume: 0.45 } });
  fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'togglePlay' });
});

test('a player that cannot seek shows its progress but is not a slider', () => {
  const { ha } = renderWithMock(
    <MediaPlayerColumn entity="ha:room" />,
    playing({ seek: false }),
    library,
  );

  expect(screen.queryByRole('slider', { name: 'Position' })).toBeNull();
  expect(screen.getByText(/0:3\d \/ 2:00/)).toBeTruthy();
  expect(ha.calls.filter((call) => call.command === 'seek')).toHaveLength(0);
});

test('the column’s browse button opens the player’s library, and false leaves it out', async () => {
  const { unmount } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
  fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
  expect(await screen.findByRole('tab', { name: 'Albums' })).toBeTruthy();
  unmount();

  renderWithMock(<MediaPlayerColumn entity="ha:room" browse={false} />, playing(), library);
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
});

test('no browse button for a player without a library', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing({ browse: false }), library);
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
});

test('the page puts the library beside the player, and picks a song right there', async () => {
  const { ha } = renderWithMock(<RoomPage entity="ha:room" />, playing(), library);
  expect(screen.getByRole('heading', { name: 'Dreams' })).toBeTruthy();
  // The library opens on its first shelf, so a song is one tap away.
  fireEvent.click(await screen.findByRole('button', { name: 'Play Kids' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'playMedia', args: { item: 't-kids' } });
});

test('the page has no library column for a player without one', () => {
  renderWithMock(<RoomPage entity="ha:room" />, playing({ browse: false }), library);
  expect(screen.getByRole('heading', { name: 'Dreams' })).toBeTruthy();
  expect(screen.queryByRole('searchbox')).toBeNull();
  expect(screen.queryByRole('tab')).toBeNull();
});

test('the bar shows a progress line you can seek on, the time beside the artist, and the browser by default', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
  const line = screen.getByRole('slider', { name: 'Position' });
  expect(Number(line.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(30);
  expect(line.getAttribute('aria-valuemax')).toBe('120');
  expect(screen.getByText(/0:3\d \/ 2:00/)).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Browse media' })).toBeTruthy();
  fireEvent.keyDown(line, { key: 'ArrowRight' });
  expect(ha.calls.at(-1)).toMatchObject({
    command: 'seek',
    args: { position: expect.any(Number) },
  });
});

test('the bar can leave the time out and still has its line to show and seek on', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" time={false} />, playing(), library);
  expect(screen.queryByText(/ \/ 2:00/)).toBeNull();
  expect(screen.getByRole('slider', { name: 'Position' })).toBeTruthy();
});

test('the bar can leave the shuffle button out', () => {
  const { unmount } = renderWithMock(
    <MediaPlayerBar entity="ha:room" />,
    playing({ shuffle: true }),
    library,
  );

  expect(screen.getByRole('button', { name: 'Shuffle' })).toBeTruthy();
  unmount();
  renderWithMock(
    <MediaPlayerBar entity="ha:room" shuffle={false} />,
    playing({ shuffle: true }),
    library,
  );

  expect(screen.queryByRole('button', { name: 'Shuffle' })).toBeNull();
});

test('dragging the bar’s line moves the time with it, and lets go with a seek', () => {
  const box = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    right: 200,
    bottom: 16,
    width: 200,
    height: 16,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });

  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
  const line = screen.getByRole('slider', { name: 'Position' });
  fireEvent.pointerDown(line, { clientX: 100, pointerId: 1 });
  expect(screen.getByText('1:00 / 2:00')).toBeTruthy();
  fireEvent.pointerMove(line, { clientX: 150, pointerId: 1 });
  expect(screen.getByText('1:30 / 2:00')).toBeTruthy();
  expect(ha.calls.filter((call) => call.command === 'seek')).toHaveLength(0);
  fireEvent.pointerUp(line, { clientX: 150, pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 90 } });
  box.mockRestore();
});

test('a bar whose player cannot seek shows the line but it is only a progress indicator', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, playing({ seek: false }), library);
  expect(screen.queryByRole('slider', { name: 'Position' })).toBeNull();
  expect(screen.getByRole('progressbar', { name: 'Position' })).toBeTruthy();
  expect(screen.getByText(/0:3\d \/ 2:00/)).toBeTruthy();
});

test('dragging around the ring moves the time under the title with it', () => {
  const box = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
    left: 0,
    top: 0,
    right: 168,
    bottom: 168,
    width: 168,
    height: 168,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });

  const { ha } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
  const ring = screen.getByRole('slider', { name: 'Position' });
  // 3 o'clock is a quarter of the way round: 30s of 120.
  fireEvent.pointerDown(ring, { clientX: 160, clientY: 84, pointerId: 1 });
  expect(screen.getByText('0:30 / 2:00')).toBeTruthy();
  // 6 o'clock is half way.
  fireEvent.pointerMove(ring, { clientX: 84, clientY: 160, pointerId: 1 });
  expect(screen.getByText('1:00 / 2:00')).toBeTruthy();
  fireEvent.pointerUp(ring, { clientX: 84, clientY: 160, pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 60 } });
  box.mockRestore();
});

describe('the ring stops at the start and the end instead of wrapping round the top', () => {
  // A 168px ring centred on (84, 84), 120 seconds long: 12 o'clock is both the start and the end.
  const AT = {
    justAfterTop: { clientX: 100, clientY: 10, pointerId: 1 },
    top: { clientX: 84, clientY: 8, pointerId: 1 },
    justBeforeTop: { clientX: 60, clientY: 10, pointerId: 1 },
  };

  const setup = () => {
    const box = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      right: 168,
      bottom: 168,
      width: 168,
      height: 168,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });

    const view = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    return { ...view, ring: screen.getByRole('slider', { name: 'Position' }), box };
  };

  test('dragging back past the start holds it at the start, not near the end', () => {
    const { ha, ring, box } = setup();
    fireEvent.pointerDown(ring, AT.justAfterTop);
    expect(screen.getByText('0:04 / 2:00')).toBeTruthy();
    fireEvent.pointerMove(ring, AT.top);
    expect(screen.getByText('0:00 / 2:00')).toBeTruthy();
    // Past the top the other way: used to be 1:54, nearly the end.
    fireEvent.pointerMove(ring, AT.justBeforeTop);
    expect(screen.getByText('0:00 / 2:00')).toBeTruthy();
    fireEvent.pointerUp(ring, AT.justBeforeTop);
    expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 0 } });
    box.mockRestore();
  });

  test('and it only moves again once the pointer is back round, so it stays under the pointer', () => {
    const { ring, box } = setup();
    fireEvent.pointerDown(ring, AT.justAfterTop);
    fireEvent.pointerMove(ring, AT.justBeforeTop);
    expect(screen.getByText('0:00 / 2:00')).toBeTruthy();
    // Back to the top: still the start. Past it: it moves.
    fireEvent.pointerMove(ring, AT.top);
    expect(screen.getByText('0:00 / 2:00')).toBeTruthy();
    fireEvent.pointerMove(ring, AT.justAfterTop);
    expect(screen.getByText('0:04 / 2:00')).toBeTruthy();
    box.mockRestore();
  });

  test('dragging on past the end holds it at the end, not back at the start', () => {
    const { ha, ring, box } = setup();
    fireEvent.pointerDown(ring, AT.justBeforeTop);
    expect(screen.getByText('1:54 / 2:00')).toBeTruthy();
    fireEvent.pointerMove(ring, AT.top);
    expect(screen.getByText('2:00 / 2:00')).toBeTruthy();
    fireEvent.pointerMove(ring, AT.justAfterTop);
    expect(screen.getByText('2:00 / 2:00')).toBeTruthy();
    fireEvent.pointerUp(ring, AT.justAfterTop);
    expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 120 } });
    box.mockRestore();
  });
});

test('the bar leaves the browse button out for browse={false} and for a player with no library', () => {
  const { unmount } = renderWithMock(
    <MediaPlayerBar entity="ha:room" browse={false} />,
    playing(),
    library,
  );

  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
  unmount();

  renderWithMock(<MediaPlayerBar entity="ha:room" />, playing({ browse: false }), library);
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
  expect(screen.queryByRole('slider', { name: 'Position' })).not.toBeNull();
});

test('the artwork sits in a ring that shows how far along playback is, with the time under the title', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
  const ring = screen.getByRole('slider', { name: 'Position' });
  expect(ring.getAttribute('aria-valuemax')).toBe('120');
  expect(Number(ring.getAttribute('aria-valuenow'))).toBeGreaterThanOrEqual(30);
  expect(screen.getByText(/0:3\d \/ 2:00/)).toBeTruthy();
});

test('a player that reports no position shows the ring without progress or a time', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" />, {
    room: mockMediaPlayer({ name: 'Room', playback: 'paused', media: { title: 'Dreams' } }),
  });

  expect(screen.queryByRole('slider', { name: 'Position' })).toBeNull();
  expect(screen.queryByText(/\d:\d\d \/ /)).toBeNull();
  // No artwork: the circle carries a music glyph instead.
  expect(document.querySelector('img')).toBeNull();
});

test('artwork fills the circle when there is some', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" />, {
    room: mockMediaPlayer({ name: 'Room', media: { title: 'Dreams', artworkUrl: '/cover.jpg' } }),
  });

  expect(document.querySelector('img')?.getAttribute('src')).toBe('/cover.jpg');
});

test('the mute button is a plain icon like the one in the bar, and mutes', () => {
  const { ha } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
  const mute = screen.getByRole('button', { name: 'Mute' });
  expect(getComputedStyle(mute).backgroundColor).not.toBe('rgb(239, 234, 225)');
  expect(getComputedStyle(mute).borderRadius).not.toBe('50%');
  fireEvent.click(mute);
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setMuted', args: { muted: true } });
});

test('the column is only as tall as its content, even in a parent that stretches it', () => {
  const { container } = renderWithMock(
    <div style={{ display: 'flex', height: 900 }}>
      <MediaPlayerColumn entity="ha:room" />
    </div>,
    playing(),
    library,
  );

  const card = container.querySelector('[data-status]')!.parentElement as HTMLElement;
  expect(getComputedStyle(card).alignSelf).toBe('flex-start');
});

test('shuffle sits left of the transport and toggles, with play/pause kept in the middle', () => {
  const { ha, container } = renderWithMock(
    <MediaPlayerColumn entity="ha:room" />,
    {
      room: mockMediaPlayer({
        name: 'Room',
        playback: 'playing',
        shuffle: false,
        capabilities: { browse: true, shuffle: true },
      }),
    },
    library,
  );

  const shuffle = screen.getByRole('button', { name: 'Shuffle' });
  expect(shuffle.getAttribute('aria-pressed')).toBe('false');
  fireEvent.click(shuffle);
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setShuffle', args: { shuffle: true } });
  expect(screen.getByRole('button', { name: 'Shuffle' }).getAttribute('aria-pressed')).toBe('true');

  const row = screen.getByRole('button', { name: 'Pause' }).parentElement as HTMLElement;
  expect(row.children).toHaveLength(5);
  expect(row.children[2]).toBe(screen.getByRole('button', { name: 'Pause' }));
  expect(container).toBeTruthy();
});

test('in the drawer, shuffle sits by the title and the transport is only previous, play and next', () => {
  renderWithMock(
    <MediaPlayerColumn entity="ha:room" />,
    {
      room: mockMediaPlayer({
        name: 'Room',
        playback: 'playing',
        media: { title: 'Dreams' },
        shuffle: false,
        capabilities: { browse: true, shuffle: true },
      }),
    },
    library,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
  const [, shuffle] = screen.getAllByRole('button', { name: 'Shuffle' }) as [
    HTMLElement,
    HTMLElement,
  ];

  const [, title] = screen.getAllByRole('heading', { name: 'Dreams' }) as [
    HTMLElement,
    HTMLElement,
  ];

  expect(shuffle.parentElement).toBe(title.parentElement);

  const [, pause] = screen.getAllByRole('button', { name: 'Pause' }) as [HTMLElement, HTMLElement];
  const transport = Array.from(pause.parentElement!.children);
  expect(transport.filter((child) => child.getAttribute('aria-label') === 'Shuffle')).toEqual([]);
  expect(transport[2]).toBe(pause);
});

describe('name', () => {
  const idle = () => ({
    room: mockMediaPlayer({ name: 'Room', playback: 'paused', capabilities: { browse: true } }),
  });

  test('the column calls the player what it is told, on the card and as the drawer title', () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" name="Kitchen speaker" />, idle(), library);
    expect(screen.getByText('Kitchen speaker')).toBeTruthy();
    expect(screen.queryByText('Room')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    expect(screen.getAllByText('Kitchen speaker').length).toBeGreaterThan(1);
  });

  test('without one it uses the name the player reports', () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, idle(), library);
    expect(screen.getByText('Room')).toBeTruthy();
  });

  test('the bar names the drawer, and the page shows the name', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" name="Kitchen speaker" />, idle(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    expect(screen.getAllByText('Kitchen speaker').length).toBeGreaterThan(0);
    cleanup();

    renderWithMock(<RoomPage entity="ha:room" name="Kitchen speaker" />, idle(), library);
    expect(screen.getByText('Kitchen speaker')).toBeTruthy();
  });
});

test('play/pause stays in the middle with no shuffle and no browse button', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" />, {
    room: mockMediaPlayer({ name: 'Room', playback: 'playing' }),
  });

  expect(screen.queryByRole('button', { name: 'Shuffle' })).toBeNull();
  const row = screen.getByRole('button', { name: 'Pause' }).parentElement as HTMLElement;
  expect(row.children).toHaveLength(5);
  expect(row.children[2]).toBe(screen.getByRole('button', { name: 'Pause' }));
});

test('the bar has a shuffle toggle by the title when the player can shuffle, and none otherwise', () => {
  const { ha, unmount } = renderWithMock(
    <MediaPlayerBar entity="ha:room" />,
    playing({ shuffle: true }),
    library,
  );

  const shuffle = screen.getByRole('button', { name: 'Shuffle' });
  expect(shuffle.getAttribute('aria-pressed')).toBe('false');
  // Next to the song title, not among the transport buttons.
  expect(shuffle.parentElement?.contains(screen.getByText('Dreams'))).toBe(true);
  fireEvent.click(shuffle);
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setShuffle', args: { shuffle: true } });
  unmount();

  renderWithMock(<MediaPlayerBar entity="ha:room" />, playing({ shuffle: false }), library);
  expect(screen.queryByRole('button', { name: 'Shuffle' })).toBeNull();
});

test('the bar’s time is its own element beside the track info, not a line inside it', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
  const time = screen.getByText(/0:3\d \/ 2:00/);
  const info = screen.getByText('Fleetwood Mac').closest('[data-part="track"]') as HTMLElement;
  expect(info.contains(screen.getByText('Dreams'))).toBe(true);
  // A sibling of the track info in the bar (which centers its items), so the time sits at the
  // bar's vertical middle rather than in one of the info's lines.
  expect(info.contains(time)).toBe(false);
  expect(time.parentElement).toBe(info.parentElement);
});

describe('holding the horizontal bar', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const bar = () => screen.getByText('Dreams').closest('[data-status]') as HTMLElement;
  const press = (target: HTMLElement, x = 10, y = 10) =>
    fireEvent.pointerDown(target, { clientX: x, clientY: y, pointerId: 1 });

  test('a hold opens the drawer with the player and the library below it', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
    press(bar());
    act(() => vi.advanceTimersByTime(500));
    // The vertical player: the volume bar is always there, and the library follows it.
    expect(screen.getByRole('slider', { name: 'Volume level' })).toBeTruthy();
    expect(screen.getAllByRole('heading', { name: 'Dreams' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('searchbox', { name: 'Search the library' })).toBeTruthy();
  });

  test('a press that ends early, or moves away, opens nothing', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    press(bar());
    act(() => vi.advanceTimersByTime(300));
    fireEvent.pointerUp(bar(), { pointerId: 1 });
    act(() => vi.advanceTimersByTime(500));
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();

    press(bar(), 10, 10);
    fireEvent.pointerMove(bar(), { clientX: 60, clientY: 10, pointerId: 1 });
    act(() => vi.advanceTimersByTime(600));
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  });

  test('a press that starts on a button is that button’s, not a hold', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    press(screen.getByRole('button', { name: 'Next' }));
    act(() => vi.advanceTimersByTime(600));
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  });

  test('the click that ends a hold is cancelled, and an ordinary click is not', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    // Taken before the drawer opens: its player repeats the title.
    const card = bar();
    expect(fireEvent.click(card)).toBe(true);
    press(card);
    act(() => vi.advanceTimersByTime(500));
    expect(fireEvent.click(card)).toBe(false);
    // Only the one click after a hold is swallowed.
    expect(fireEvent.click(card)).toBe(true);
  });

  test('a player that is not ready has no drawer to hold open', () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, {
      room: mockMediaPlayer({ name: 'Room', availability: 'unavailable', playback: 'off' }),
    });

    press(screen.getByText('Unavailable').closest('[data-status]') as HTMLElement);
    act(() => vi.advanceTimersByTime(600));
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  });
});

describe('the vertical card’s artwork', () => {
  test('opens the library in the drawer, like the horizontal card, and so does the browse button', async () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    expect(screen.queryByRole('searchbox')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    expect(await screen.findByRole('tab', { name: 'Albums' })).toBeTruthy();
  });

  test('is only artwork when there is nothing to open', () => {
    const { unmount } = renderWithMock(
      <MediaPlayerColumn entity="ha:room" />,
      playing({ browse: false }),
      library,
    );

    expect(screen.queryByRole('button', { name: 'Open media browser' })).toBeNull();
    unmount();
    renderWithMock(<MediaPlayerColumn entity="ha:room" browse={false} />, playing(), library);
    expect(screen.queryByRole('button', { name: 'Open media browser' })).toBeNull();
  });

  test('the page keeps its library beside the player, so its artwork opens nothing', () => {
    renderWithMock(<RoomPage entity="ha:room" />, playing(), library);
    expect(screen.queryByRole('button', { name: 'Open media browser' })).toBeNull();
  });

  test('pressing the artwork opens the drawer without also seeking, and the ring still seeks', () => {
    const box = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0,
      top: 0,
      right: 168,
      bottom: 168,
      width: 168,
      height: 168,
      x: 0,
      y: 0,
      toJSON: () => ({}),
    });

    const { ha } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    const artwork = screen.getByRole('button', { name: 'Open media browser' });
    fireEvent.pointerDown(artwork, { clientX: 84, clientY: 84, pointerId: 1 });
    fireEvent.pointerUp(artwork, { clientX: 84, clientY: 84, pointerId: 1 });
    expect(ha.calls.filter((call) => call.command === 'seek')).toHaveLength(0);

    const ring = screen.getByRole('slider', { name: 'Position' });
    fireEvent.pointerDown(ring, { clientX: 160, clientY: 84, pointerId: 1 });
    fireEvent.pointerUp(ring, { clientX: 160, clientY: 84, pointerId: 1 });
    expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 30 } });
    box.mockRestore();
  });
});

describe('long press on the vertical card, and what each way of opening the drawer does', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const hold = (target: HTMLElement) => {
    fireEvent.pointerDown(target, { clientX: 10, clientY: 10, pointerId: 1 });
    act(() => vi.advanceTimersByTime(500));
  };

  test('holding the column opens the drawer with the player and its library', () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    expect(screen.getAllByRole('slider', { name: 'Volume level' })).toHaveLength(1);
    hold(screen.getByRole('heading', { name: 'Dreams' }));
    // The drawer repeats the player (a second volume bar) and adds the library.
    expect(screen.getAllByRole('slider', { name: 'Volume level' })).toHaveLength(2);
    expect(screen.getByRole('searchbox', { name: 'Search the library' })).toBeTruthy();
    // The side panel, not expanded.
    expect(screen.getByRole('button', { name: 'Expand' })).toBeTruthy();
  });

  test('a press on a control of the column is that control’s, and a column with no library has nothing to hold open', () => {
    const { unmount } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    hold(screen.getByRole('button', { name: 'Next' }));
    expect(screen.queryByRole('searchbox')).toBeNull();
    unmount();

    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing({ browse: false }), library);
    hold(screen.getByRole('heading', { name: 'Dreams' }));
    expect(screen.getAllByRole('slider', { name: 'Volume level' })).toHaveLength(1);
  });

  test('on the vertical card the artwork opens the side panel and the browse button opens it expanded', () => {
    const { unmount } = renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    expect(screen.getByRole('button', { name: 'Expand' })).toBeTruthy();
    unmount();

    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    expect(screen.getByRole('button', { name: 'Collapse' })).toBeTruthy();
    expect(screen.getByRole('searchbox', { name: 'Search the library' })).toBeTruthy();
  });

  test('on the horizontal card hold and the artwork open the side panel, and the browse button expands it', () => {
    const { unmount } = renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    hold(screen.getByText('Dreams').closest('[data-status]') as HTMLElement);
    expect(screen.getByRole('button', { name: 'Expand' })).toBeTruthy();
    unmount();

    const second = renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Open player' }));
    expect(screen.getByRole('button', { name: 'Expand' })).toBeTruthy();
    second.unmount();

    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    expect(screen.getByRole('button', { name: 'Collapse' })).toBeTruthy();
  });

  test('the drawer stacks the player and the library at every width, with the player centered', () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    expect(layoutOf(screen.getAllByRole('slider', { name: 'Volume level' })[1]!)).toEqual({
      ...SHAPE,
      playerMaxWidth: '420px',
    });

    // The narrow drawer stacks from the top: nothing stretches to push the library to the bottom.
    expect(roomAbove(screen.getAllByRole('slider', { name: 'Volume level' })[1]!)).toBe('0');

    // Expanded, the player is larger, and the library is docked at the bottom.
    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    expect(screen.getByRole('button', { name: 'Collapse' })).toBeTruthy();
    expect(layoutOf(screen.getAllByRole('slider', { name: 'Volume level' })[1]!)).toEqual({
      ...SHAPE,
      playerMaxWidth: '560px',
    });

    expect(roomAbove(screen.getAllByRole('slider', { name: 'Volume level' })[1]!)).toBe('1');
  });

  test('the full page is the same layout as the expanded drawer', () => {
    renderWithMock(<RoomPage entity="ha:room" />, playing(), library);
    expect(layoutOf(screen.getByRole('slider', { name: 'Volume level' }))).toEqual({
      ...SHAPE,
      playerMaxWidth: '560px',
    });
  });
});

/** What `layoutOf` reports for the big player, in the drawer and on the page alike. */
const SHAPE = {
  direction: 'column',
  playerCentered: true,
  libraryBelow: true,
};

/** How much of the room the library leaves the player takes (a flex-grow): 0 stacks from the top,
 * 1 centers the player and docks the library to the bottom. */
function roomAbove(volume: HTMLElement) {
  const search = screen.getByRole('searchbox', { name: 'Search the library' });
  let body = volume.parentElement as HTMLElement;
  while (!body.contains(search)) {
    body = body.parentElement as HTMLElement;
  }

  const area = Array.from(body.children).find((child) => child.contains(volume)) as HTMLElement;
  return getComputedStyle(area).flexGrow;
}

/** How the player and the library are arranged around a volume slider inside the big player: the
 * player sits in a box that centers it in the room above the library. */
function layoutOf(volume: HTMLElement) {
  const search = screen.getByRole('searchbox', { name: 'Search the library' });
  let body = volume.parentElement as HTMLElement;
  while (!body.contains(search)) {
    body = body.parentElement as HTMLElement;
  }

  // The top of the body is a row (the player, and the queue beside it when there is one) whose first
  // child is the box that centers the player.
  const area = Array.from(body.children).find((child) => child.contains(volume)) as HTMLElement;
  const centering = area.firstElementChild as HTMLElement;
  const player = centering.firstElementChild as HTMLElement;
  return {
    direction: getComputedStyle(body).flexDirection,
    playerCentered:
      getComputedStyle(centering).justifyContent === 'center' &&
      getComputedStyle(centering).alignItems === 'center',
    playerMaxWidth: getComputedStyle(player).maxWidth,
    libraryBelow: Boolean(area.compareDocumentPosition(search) & Node.DOCUMENT_POSITION_FOLLOWING),
  };
}

describe('the library’s layout follows the space it has', () => {
  const rows = () => screen.getAllByRole('list').at(-1) as HTMLElement;

  test('the full page lays the library out as a theater row', async () => {
    renderWithMock(<RoomPage entity="ha:room" />, playing(), library);
    await screen.findByRole('button', { name: 'Play Dreams' });
    expect(getComputedStyle(rows()).overflowX).toBe('auto');
    expect(getComputedStyle(rows()).flexDirection).not.toBe('column');
  });

  test('the collapsed drawer keeps rows, and expanding it makes the same theater row', async () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    await screen.findAllByRole('button', { name: 'Play Dreams' });
    expect(getComputedStyle(rows()).flexDirection).toBe('column');
    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    expect(getComputedStyle(rows()).overflowX).toBe('auto');
    expect(getComputedStyle(rows()).flexDirection).not.toBe('column');
    fireEvent.click(screen.getByRole('button', { name: 'Collapse' }));
    expect(getComputedStyle(rows()).flexDirection).toBe('column');
  });

  test('the browse button opens the drawer expanded, so it opens in theater', async () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing(), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    await screen.findAllByRole('button', { name: 'Play Dreams' });
    expect(getComputedStyle(rows()).overflowX).toBe('auto');
  });
});

describe('the queue beside the player', () => {
  const queue = <div>the queue</div>;

  test('a wide player puts the queue beside it, not below it', () => {
    renderWithMock(
      <MediaPlayerFull entity="ha:room" wide queue={queue} />,
      playing({ queue: true }),
      library,
    );

    const beside = screen.getByText('the queue');
    const volume = screen.getByRole('slider', { name: 'Volume level' });
    // Both are in the same row at the top: the closest thing that holds both is a row.
    let row = beside;
    while (!row.contains(volume)) {
      row = row.parentElement!;
    }

    expect(getComputedStyle(row).flexDirection).not.toBe('column');
    // The queue's own column is as tall as the row, with a line on its left.
    const column = beside.parentElement!.parentElement!;
    expect(computed(column, 'border-left')).toContain('1px');
    expect(computed(column, 'flex')).toBe('0 0 auto');
  });

  test('a narrow one has no room for it, when it is asked not to take turns with the library', () => {
    renderWithMock(
      <MediaPlayerFull entity="ha:room" wide={false} tabs={false} queue={queue} />,
      playing({ queue: true }),
      library,
    );

    expect(screen.queryByText('the queue')).toBeNull();
  });
});

describe('the queue in the expanded drawer of a media card', () => {
  test('the vertical card’s expanded drawer shows the queue beside the player, when the player has one', async () => {
    renderWithMock(<MediaPlayerColumn entity="ha:room" />, playing({ queue: true }), library);
    fireEvent.click(screen.getByRole('button', { name: 'Open media browser' }));
    // Collapsed, there is no room for it.
    expect(screen.queryByText(/ tracks$/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Expand' }));
    expect(await screen.findByText(/24 tracks/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Clear the queue' })).toBeTruthy();
  });

  test('the bar’s browse button opens the drawer expanded, with the queue too, and a player with no queue has no empty column', async () => {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing({ queue: true }), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    expect(await screen.findByText(/24 tracks/)).toBeTruthy();
    cleanup();

    renderWithMock(<MediaPlayerBar entity="ha:room" />, playing({ queue: false }), library);
    fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
    await screen.findAllByRole('button', { name: 'Play Dreams' });
    expect(screen.queryByText(/ tracks$/)).toBeNull();
    expect(screen.queryByRole('button', { name: 'Clear the queue' })).toBeNull();
  });
});

test('the column with overlays opens the library and the queue full size, each alone, and holding opens nothing', async () => {
  renderWithMock(
    <MediaPlayerColumn entity="ha:room" overlays />,
    playing({ queue: true }),
    library,
  );

  // Library left of the transport, the queue right of it; shuffle is not in the row.
  const labels = screen.getAllByRole('button').map((b) => b.getAttribute('aria-label'));
  expect(labels.indexOf('Browse media')).toBeLessThan(labels.indexOf('Previous'));
  expect(labels.indexOf('Queue')).toBeGreaterThan(labels.indexOf('Next'));

  fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
  expect(await screen.findByText('Playlists')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /^(Collapse|Expand)$/ })).toBeNull();
  // The player is not drawn again inside the overlay.
  expect(screen.getAllByText('Dreams')).toHaveLength(1);
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));

  fireEvent.click(screen.getByRole('button', { name: 'Queue' }));
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
  expect(screen.queryByText('Playlists')).toBeNull();
});

test('the column with overlays has no buttons for a library or queue the player lacks, and the artwork opens nothing', () => {
  renderWithMock(<MediaPlayerColumn entity="ha:room" overlays browse={false} />, playing());
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Queue' })).toBeNull();
  expect(screen.queryByRole('button', { name: /Artwork|Open/ })).toBeNull();
});

// Library and Queue as tabs below the player, where the queue has no room beside it.

function fullPlayer(props: Partial<ComponentProps<typeof MediaPlayerFull>> = {}) {
  return (
    <MediaPlayerFull
      entity="ha:room"
      browser={<MediaBrowser entity="ha:room" layout="list" />}
      queue={<MediaQueue entity="ha:room" />}
      {...props}
    />
  );
}

const tab = (name: string) => screen.getByRole('tab', { name });

test('the full player has Library and Queue tabs below it, the library first, and a tab swaps the list', async () => {
  renderWithMock(fullPlayer(), playing({ queue: true }), library);

  expect(screen.getByRole('tablist', { name: 'Library or queue' })).toBeTruthy();
  expect(tab('Library').getAttribute('aria-selected')).toBe('true');
  expect(tab('Queue').getAttribute('aria-selected')).toBe('false');
  expect(await screen.findByText('Playlists')).toBeTruthy();
  expect(screen.queryByText(/tracks/)).toBeNull();

  fireEvent.click(tab('Queue'));
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
  expect(screen.queryByText('Playlists')).toBeNull();
  expect(tab('Queue').getAttribute('aria-selected')).toBe('true');

  // The player is where it was, and not drawn again.
  expect(screen.getAllByRole('heading', { name: 'Dreams' })).toHaveLength(1);
  fireEvent.click(tab('Library'));
  expect(await screen.findByText('Playlists')).toBeTruthy();
});

test('tabs={false} leaves the library alone, defaultTab opens the queue first', async () => {
  const { unmount } = renderWithMock(
    fullPlayer({ tabs: false }),
    playing({ queue: true }),
    library,
  );

  expect(screen.queryByRole('tablist')).toBeNull();

  expect(await screen.findByText('Playlists')).toBeTruthy();
  unmount();

  renderWithMock(fullPlayer({ defaultTab: 'queue' }), playing({ queue: true }), library);
  expect(tab('Queue').getAttribute('aria-selected')).toBe('true');
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
});

test('a tab that is kept by the caller is the open one, and a change is asked for', async () => {
  const onTabChange = vi.fn<(next: 'library' | 'queue' | 'speakers') => void>();
  renderWithMock(fullPlayer({ tab: 'queue', onTabChange }), playing({ queue: true }), library);

  expect(tab('Queue').getAttribute('aria-selected')).toBe('true');
  fireEvent.click(tab('Library'));
  expect(onTabChange).toHaveBeenCalledWith('library');
});

test('no tabs without a queue to switch to, in the wide layout, or with only one list', async () => {
  // A player with no queue: just its library, under a plain line.
  const { unmount } = renderWithMock(fullPlayer(), playing(), library);
  expect(screen.queryByRole('tablist')).toBeNull();

  expect(await screen.findByText('Playlists')).toBeTruthy();
  unmount();

  // Wide, the queue is beside the player instead.
  const wide = renderWithMock(fullPlayer({ wide: true }), playing({ queue: true }), library);
  expect(screen.queryByRole('tablist')).toBeNull();
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
  wide.unmount();

  // Only a queue: it is shown, with nothing to switch to.
  renderWithMock(
    <MediaPlayerFull entity="ha:room" queue={<MediaQueue entity="ha:room" />} />,
    playing({ queue: true }),
    library,
  );

  expect(screen.queryByRole('tablist')).toBeNull();
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
});

describe('the speaker dropdown in the full player', () => {
  const players = [
    { entity: 'ha:room' as const, name: 'Kitchen' },
    { entity: 'ha:other' as const, name: 'Bathroom' },
  ];

  test('names the speaker and lists the others to pick from', () => {
    const onPlayerChange = vi.fn<(entity: string) => void>();
    renderWithMock(
      <MediaPlayerFull entity="ha:room" players={players} onPlayerChange={onPlayerChange} />,
      playing(),
    );

    const trigger = screen.getByRole('button', { name: 'Speaker' });
    expect(trigger.textContent).toContain('Kitchen');
    expect(screen.queryByRole('listbox')).toBeNull();

    fireEvent.click(trigger);

    expect(screen.getByRole('option', { name: 'Kitchen' }).getAttribute('aria-selected')).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('option', { name: 'Bathroom' }));

    expect(onPlayerChange).toHaveBeenCalledWith('ha:other');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  test('is left out with one speaker, or without a way to change it', () => {
    renderWithMock(
      <MediaPlayerFull
        entity="ha:room"
        players={players.slice(0, 1)}
        onPlayerChange={vi.fn<(entity: string) => void>()}
      />,
      playing(),
    );

    expect(screen.queryByRole('button', { name: 'Speaker' })).toBeNull();

    cleanup();
    renderWithMock(<MediaPlayerFull entity="ha:room" players={players} />, playing());
    expect(screen.queryByRole('button', { name: 'Speaker' })).toBeNull();
  });

  test('closes on Escape', () => {
    renderWithMock(
      <MediaPlayerFull
        entity="ha:room"
        players={players}
        onPlayerChange={vi.fn<(entity: string) => void>()}
      />,
      playing(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Speaker' }));

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(screen.queryByRole('listbox')).toBeNull();
  });
});

describe('the compact full player', () => {
  test('volume and speakers are round buttons, and the volume replaces the button row, with a cross to give it back', async () => {
    renderWithMock(<MediaPlayerFull entity="ha:room" compact speakers={['ha:other']} />, {
      ...playing({ group: true }),
      other: mockMediaPlayer({ name: 'Other', playback: 'idle' }),
    });

    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
    expect(screen.queryByText('Speakers')).toBeNull();
    expect(screen.getByRole('button', { name: /^Speakers/ })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Volume' }));

    expect(await screen.findByRole('slider', { name: 'Volume level' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Pause' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Close volume' }));

    expect(await screen.findByRole('button', { name: 'Pause' })).toBeTruthy();
    expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  });

  test('without it the slider is always there', () => {
    renderWithMock(<MediaPlayerFull entity="ha:room" />, playing());

    expect(screen.getByRole('slider', { name: 'Volume level' })).toBeTruthy();
  });
});

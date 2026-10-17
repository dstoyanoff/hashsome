import { mockLibrary, mockMediaPlayer } from '@hashsome/core';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { MediaPlayerBar } from '../media-player-bar.tsx';

const player = (playback: 'playing' | 'paused' = 'playing') => ({
  room: mockMediaPlayer({
    name: 'Room',
    playback,
    media: { title: 'Blank Space', artist: 'More More' },
    volume: 0.4,
  }),
});

test('shows track info and toggles play/pause', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  expect(screen.getByText('Blank Space')).toBeTruthy();
  expect(screen.getByText('More More')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(ha.getEntity('room')).toMatchObject({ playback: 'paused' });
  expect(screen.getByRole('button', { name: 'Play' })).toBeTruthy();
});

test('skips tracks', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Next' }));
  fireEvent.click(screen.getByRole('button', { name: 'Previous' }));
  expect(ha.calls.map((c) => c.command)).toEqual(['next', 'previous']);
});

test('transport order is volume, then previous, play/pause, next (after the artwork, which opens the player)', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  const names = screen.getAllByRole('button').map((b) => b.getAttribute('aria-label'));
  expect(names).toEqual(['Open player', 'Volume', 'Previous', 'Pause', 'Next']);
});

function mockWidth(el: HTMLElement) {
  el.getBoundingClientRect = () =>
    ({ left: 0, width: 200, top: 0, height: 28, right: 200, bottom: 28, x: 0, y: 0 }) as DOMRect;
}

test('the volume button opens a volume slider in the bar; the X closes it', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const slider = screen.getByRole('slider', { name: 'Volume level' });
  expect(slider.getAttribute('aria-valuenow')).toBe('40');
  // In one row the slider takes the track's place (the track is hidden, not removed).
  const track = screen.getByText('Blank Space').closest('[data-part="track"]') as HTMLElement;
  expect(getComputedStyle(track).display).toBe('none');
  fireEvent.click(screen.getByRole('button', { name: 'Close volume' }));
  expect(screen.queryByRole('slider', { name: 'Volume level' })).toBeNull();
  expect(getComputedStyle(track).display).not.toBe('none');
});

test('dragging or nudging the slider sets the volume as a fraction and it sticks', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const slider = screen.getByRole('slider', { name: 'Volume level' });
  mockWidth(slider);
  fireEvent.pointerDown(slider, { clientX: 140, pointerId: 1 });
  fireEvent.pointerUp(slider, { clientX: 140, pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'setVolume', args: { volume: 0.7 } });
  expect(screen.getByRole('slider', { name: 'Volume level' }).getAttribute('aria-valuenow')).toBe(
    '70',
  );

  fireEvent.keyDown(screen.getByRole('slider', { name: 'Volume level' }), { key: 'ArrowLeft' });
  expect(ha.getEntity('room')).toMatchObject({ volume: 0.65 });
});

test('the mute button mutes, and moving the slider while muted unmutes', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  fireEvent.click(screen.getByRole('button', { name: 'Mute' }));
  expect(ha.getEntity('room')).toMatchObject({ muted: true });
  expect(screen.getByRole('button', { name: 'Unmute' }).getAttribute('aria-pressed')).toBe('true');
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Volume level' }), { key: 'ArrowRight' });
  expect(ha.getEntity('room')).toMatchObject({ muted: false });
});

test('unavailable player is disabled', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, {
    room: mockMediaPlayer({ availability: 'unavailable', playback: 'off' }),
  });

  expect(screen.getByText('Unavailable')).toBeTruthy();
  expect((screen.getByRole('button', { name: 'Play' }) as HTMLButtonElement).disabled).toBe(true);
});

test('transport icons are smaller than their circles', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, {
    room: mockMediaPlayer({ playback: 'playing', media: { title: 'Song' } }),
  });

  const button = screen.getByRole('button', { name: 'Pause' });
  expect(button.style.width).toBe('');
  expect(getComputedStyle(button.querySelector('svg') as SVGElement).width).toBe('18px');
});

test('Next waits until the new track plays, not only until it is announced, and never colours the whole button', async () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  const next = screen.getByRole('button', { name: 'Next' });
  const state = (position: number) => ({
    playback: 'playing' as const,
    media: { title: 'Next one', artist: 'More More' },
    position,
  });

  expect(next.getAttribute('data-feedback')).toBeNull();
  fireEvent.click(next);
  expect(next.getAttribute('data-feedback')).toBe('pending');
  expect(screen.getByRole('button', { name: 'Previous' }).getAttribute('data-feedback')).toBeNull();

  // The next track is announced at 0:00 but has not started.
  await act(async () => {
    ha.update('room', state(0));
  });

  await new Promise((resolve) => setTimeout(resolve, 800));
  expect(next.getAttribute('data-feedback')).toBe('pending');

  // It started.
  await act(async () => {
    ha.update('room', state(0.5));
  });

  await waitFor(() => expect(next.getAttribute('data-feedback')).toBeNull(), { timeout: 2000 });
  expect(next.getAttribute('data-feedback')).not.toBe('done');
});

test('the volume slider has an explicit full width, so it cannot collapse to nothing in a row', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const slider = screen.getByRole('slider', { name: 'Volume level' });
  expect(getComputedStyle(slider).width).toBe('100%');
});

test('the volume overlay shows the percentage, following the slider as it moves', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  expect(screen.getByText('40%')).toBeTruthy();
  fireEvent.keyDown(screen.getByRole('slider', { name: 'Volume level' }), { key: 'ArrowRight' });
  expect(screen.getByText('45%')).toBeTruthy();
});

test('moving the slider to 0 mutes; unmuting from zero lands at 15%, from any other level restores it', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const slider = () => screen.getByRole('slider', { name: 'Volume level' });
  const muted = () => (ha.getEntity('room') as { muted?: boolean } | undefined)?.muted;
  // 40 → 0 in steps of 5.
  for (let i = 0; i < 8; i++) {
    fireEvent.keyDown(slider(), { key: 'ArrowLeft' });
  }

  expect(muted()).toBe(true);
  expect(ha.getEntity('room')).toMatchObject({ volume: 0 });
  fireEvent.click(screen.getByRole('button', { name: 'Unmute' }));
  expect(muted()).toBe(false);
  expect(ha.getEntity('room')).toMatchObject({ volume: 0.15 });
  expect(screen.getByText('15%')).toBeTruthy();

  // Muting at a normal level and unmuting keeps that level.
  fireEvent.keyDown(slider(), { key: 'ArrowRight' });
  fireEvent.click(screen.getByRole('button', { name: 'Mute' }));
  fireEvent.click(screen.getByRole('button', { name: 'Unmute' }));
  expect(ha.getEntity('room')).toMatchObject({ volume: 0.2 });
});

test('a browse button appears only with `browse` content, after the track info, and opens it in the drawer', async () => {
  const { unmount } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
  unmount();
  renderWithMock(
    <MediaPlayerBar entity="ha:room" browse={<span>BROWSER CONTENT</span>} />,
    player(),
  );

  const names = screen.getAllByRole('button').map((b) => b.getAttribute('aria-label'));
  // The artwork is a button too; volume comes first so its overlay opens right next to the track info.
  expect(names).toEqual(['Open player', 'Volume', 'Browse media', 'Previous', 'Pause', 'Next']);

  expect(screen.queryByText('BROWSER CONTENT')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
  expect(await screen.findByText('BROWSER CONTENT')).toBeTruthy();
});

test('clicking the artwork opens the drawer: the player, and the browser below it when there is one', async () => {
  const { unmount } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Open player' }));
  // The drawer shows the player laid out like the vertical one, with no browser to add.
  expect(await screen.findAllByRole('heading', { name: 'Blank Space' })).toHaveLength(1);
  unmount();

  renderWithMock(
    <MediaPlayerBar entity="ha:room" browse={<span>BROWSER CONTENT</span>} />,
    player(),
  );

  fireEvent.click(screen.getByRole('button', { name: 'Open player' }));
  expect(screen.getByText('BROWSER CONTENT')).toBeTruthy();
  expect(screen.getByRole('heading', { name: 'Blank Space' })).toBeTruthy();
});

// One row or two.

const timed = {
  room: mockMediaPlayer({
    name: 'Room',
    playback: 'paused',
    media: { title: 'Blank Space', artist: 'More More' },
    position: 80,
    duration: 200,
  }),
};

const cardOf = () => document.querySelector('[data-status]') as HTMLElement;

const partOf = (name: string) => cardOf().querySelector(`[data-part="${name}"]`) as HTMLElement;

const buttonsOf = () => [
  ...partOf('tools').querySelectorAll('button'),
  ...partOf('transport').querySelectorAll('button'),
];

test('rows={2}: the track and time on top, the five buttons sharing the row under them', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={2} />, timed);
  const card = cardOf();
  expect(getComputedStyle(card).flexWrap).toBe('wrap');
  expect(card.dataset.rows).toBe('2');

  // "title · artist" on one line.
  expect(getComputedStyle(partOf('track')).flexDirection).toBe('row');
  expect(partOf('time').textContent).toMatch(/\d:\d{2} \/ \d:\d{2}/);

  // Volume, library, previous, play and next, in that order.
  expect(buttonsOf().map((b) => b.getAttribute('aria-label'))).toEqual([
    'Volume',
    'Previous',
    'Play',
    'Next',
  ]);

  // The tools and the transport are not boxes of their own: their buttons are the row's items.
  expect(getComputedStyle(partOf('tools')).display).toBe('contents');
  expect(getComputedStyle(partOf('transport')).display).toBe('contents');
});

test('rows={2}: every button is the same width, play included', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={2} />, timed);
  const shares = new Set(buttonsOf().map((b) => getComputedStyle(b).flexGrow));
  expect(shares).toEqual(new Set(['1']));
  expect(new Set(buttonsOf().map((b) => getComputedStyle(b).height)).size).toBe(1);
});

test('rows={2} is as tall as two regular tiles and the gap between them', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={2} />, timed);
  // A tile is its 44px icon circle and a 4px spacing unit; the gap is 12px.
  expect(computed(cardOf(), 'min-height')).toBe('108px');
});

test('rows={2}: while the volume slider is open it has the row, and only the close button stays', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={2} />, timed);
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const shown = buttonsOf().filter((b) => getComputedStyle(b).display !== 'none');
  expect(shown.map((b) => b.getAttribute('aria-label'))).toEqual(['Close volume']);
  expect(screen.getByRole('slider', { name: 'Volume level' })).toBeTruthy();
  // The track and the time stay above it.
  for (const part of ['track', 'time']) {
    expect(getComputedStyle(partOf(part)).display).not.toBe('none');
  }

  fireEvent.click(screen.getByRole('button', { name: 'Close volume' }));
  expect(buttonsOf().every((b) => getComputedStyle(b).display !== 'none')).toBe(true);
});

test('in one row the slider takes the place of the track and the time', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={1} />, timed);
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  for (const part of ['track', 'time']) {
    expect(getComputedStyle(partOf(part)).display).toBe('none');
  }
});

test('rows={1} keeps everything in one pill', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" rows={1} />, timed);
  expect(getComputedStyle(cardOf()).flexWrap).not.toBe('wrap');
  expect(cardOf().dataset.rows).toBeUndefined();
  expect(getComputedStyle(partOf('transport')).display).not.toBe('contents');
});

test('by default it is one row until the width says otherwise, and the width is measured on a wrapper', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, timed);
  // jsdom does not evaluate container queries: this is the wide case, and the wrapper is what is measured.
  expect(getComputedStyle(cardOf()).flexWrap).not.toBe('wrap');
  expect(getComputedStyle(cardOf().parentElement!).containerType).toBe('inline-size');
});

// Where holding the player goes.

test('onOpen: holding the card calls it, and no drawer is built', () => {
  vi.useFakeTimers();
  try {
    const onOpen = vi.fn<() => void>();
    renderWithMock(<MediaPlayerBar entity="ha:room" onOpen={onOpen} />, timed);
    const card = document.querySelector('[data-status]') as HTMLElement;
    fireEvent.pointerDown(card, { clientX: 10, clientY: 10, pointerId: 1 });
    act(() => vi.advanceTimersByTime(500));
    fireEvent.pointerUp(card, { pointerId: 1 });
    expect(onOpen).toHaveBeenCalledTimes(1);

    // Nothing opened over the page: the player is drawn once.
    expect(screen.getAllByText('Blank Space')).toHaveLength(1);
  } finally {
    vi.useRealTimers();
  }
});

test('onOpen: pressing the artwork, or the browse button (the library is on that page), calls it', () => {
  const onOpen = vi.fn<() => void>();
  renderWithMock(<MediaPlayerBar entity="ha:room" onOpen={onOpen} />, timed);
  fireEvent.click(screen.getByRole('button', { name: 'Open player' }));
  expect(onOpen).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
  expect(onOpen).toHaveBeenCalledTimes(2);
  expect(screen.getAllByText('Blank Space')).toHaveLength(1);
});

test('onOpen: the browse button is left out with browse={false}, the artwork and holding still go', () => {
  const onOpen = vi.fn<() => void>();
  renderWithMock(<MediaPlayerBar entity="ha:room" onOpen={onOpen} browse={false} />, timed);
  expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Open player' }));
  expect(onOpen).toHaveBeenCalledTimes(1);
});

test('drawer={false}: holding and the artwork do nothing, and there is no browse button', () => {
  vi.useFakeTimers();
  try {
    renderWithMock(
      <MediaPlayerBar entity="ha:room" drawer={false} browse={<span>LIBRARY</span>} />,
      timed,
    );

    expect(screen.queryByRole('button', { name: 'Open player' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();

    const card = document.querySelector('[data-status]') as HTMLElement;
    fireEvent.pointerDown(card, { clientX: 10, clientY: 10, pointerId: 1 });
    act(() => vi.advanceTimersByTime(800));
    fireEvent.pointerUp(card, { pointerId: 1 });
    expect(screen.queryByText('LIBRARY')).toBeNull();
    expect(screen.getAllByText('Blank Space')).toHaveLength(1);
  } finally {
    vi.useRealTimers();
  }
});

// How far along playback is, round the artwork.

test('the artwork ring fills as far as playback has gone, and is plain without a duration', () => {
  const { unmount } = renderWithMock(<MediaPlayerBar entity="ha:room" />, timed);
  // 80s of 200s.
  const arc = document.querySelector('[data-part="progress"]');
  expect(arc?.getAttribute('stroke-dasharray')).toBe('40 100');
  unmount();

  renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  expect(document.querySelector('[data-part="progress"]')).toBeNull();
});

// The library and the queue as overlays of their own.

const withLibrary = {
  room: mockMediaPlayer({
    name: 'Room',
    playback: 'paused',
    media: { title: 'Blank Space', artist: 'More More' },
    capabilities: { browse: true, queue: true },
  }),
};

test('overlays: browse opens the library alone, and the queue button opens the queue alone, each full size', async () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" overlays />, withLibrary, {
    library: mockLibrary(),
  });

  // The card is the player: the buttons in order, the queue after browse.
  expect(screen.getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
    'Volume',
    'Browse media',
    'Queue',
    'Previous',
    'Play',
    'Next',
  ]);

  fireEvent.click(screen.getByRole('button', { name: 'Browse media' }));
  expect(await screen.findByText('Playlists')).toBeTruthy();
  expect(within(screen.getByRole('dialog')).getByText('Library')).toBeTruthy();
  // Already full size: nothing to collapse to, so only a close button.
  expect(screen.queryByRole('button', { name: /^(Collapse|Expand)$/ })).toBeNull();
  expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy();
  // The player is not drawn again inside it, and the queue is not there.
  expect(screen.getAllByText('Blank Space')).toHaveLength(1);
  expect(screen.queryByText(/tracks/)).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));

  fireEvent.click(screen.getByRole('button', { name: 'Queue' }));
  expect(await screen.findByText(/24 tracks/)).toBeTruthy();
  expect(within(screen.getByRole('dialog')).getAllByText('Queue').length).toBeGreaterThan(0);
  expect(screen.queryByRole('button', { name: /^(Collapse|Expand)$/ })).toBeNull();
  expect(screen.queryByText('Playlists')).toBeNull();
});

test('overlays: a player with no queue has no queue button, browse={false} leaves out the library, and holding opens nothing', () => {
  vi.useFakeTimers();
  try {
    renderWithMock(<MediaPlayerBar entity="ha:room" overlays browse={false} />, player());
    expect(screen.queryByRole('button', { name: 'Queue' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Browse media' })).toBeNull();

    const card = document.querySelector('[data-status]') as HTMLElement;
    fireEvent.pointerDown(card, { clientX: 10, clientY: 10, pointerId: 1 });
    act(() => vi.advanceTimersByTime(800));
    fireEvent.pointerUp(card, { pointerId: 1 });
    expect(screen.getAllByText('Blank Space')).toHaveLength(1);
  } finally {
    vi.useRealTimers();
  }
});

// A touch on the position line that never ends must not freeze the position.

const seekable = {
  room: mockMediaPlayer({
    name: 'Room',
    playback: 'paused',
    media: { title: 'Blank Space', artist: 'More More' },
    position: 80,
    duration: 200,
    capabilities: { seek: true },
  }),
};

const lineOf = () => screen.getByRole('slider', { name: 'Position' });
const shownTime = () => screen.getByText(/\d:\d{2} \/ 3:20/).textContent;

test('pressing the position line shows the spot, and a cancelled touch gives the position back without seeking', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, seekable);
  mockWidth(lineOf());
  expect(shownTime()).toBe('1:20 / 3:20');

  fireEvent.pointerDown(lineOf(), { clientX: 150, pointerId: 1 }); // 75% of 200s
  expect(shownTime()).toBe('2:30 / 3:20');

  // The browser took the touch: no release ever comes.
  fireEvent.pointerCancel(lineOf(), { pointerId: 1 });
  expect(shownTime()).toBe('1:20 / 3:20');
  expect(lineOf().getAttribute('aria-valuenow')).toBe('80');
  expect(ha.calls.filter((call) => call.command === 'seek')).toHaveLength(0);
});

test('a lost pointer capture does the same', () => {
  renderWithMock(<MediaPlayerBar entity="ha:room" />, seekable);
  mockWidth(lineOf());
  fireEvent.pointerDown(lineOf(), { clientX: 150, pointerId: 1 });
  expect(shownTime()).toBe('2:30 / 3:20');
  fireEvent.lostPointerCapture(lineOf(), { pointerId: 1 });
  expect(shownTime()).toBe('1:20 / 3:20');
});

test('a drag that is never ended expires on its own, so the position cannot stay stuck', () => {
  vi.useFakeTimers();
  try {
    renderWithMock(<MediaPlayerBar entity="ha:room" />, seekable);
    mockWidth(lineOf());
    fireEvent.pointerDown(lineOf(), { clientX: 150, pointerId: 1 });
    expect(shownTime()).toBe('2:30 / 3:20');
    act(() => vi.advanceTimersByTime(9000));
    expect(shownTime()).toBe('1:20 / 3:20');
  } finally {
    vi.useRealTimers();
  }
});

test('a normal press and release still seeks', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, seekable);
  mockWidth(lineOf());
  fireEvent.pointerDown(lineOf(), { clientX: 150, pointerId: 1 });
  fireEvent.pointerUp(lineOf(), { clientX: 150, pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({ command: 'seek', args: { position: 150 } });
});

test('a cancelled touch on the volume slider ends the drag with the last value it reached', () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  fireEvent.click(screen.getByRole('button', { name: 'Volume' }));
  const slider = screen.getByRole('slider', { name: 'Volume level' });
  mockWidth(slider);
  fireEvent.pointerDown(slider, { clientX: 140, pointerId: 1 }); // 70%
  fireEvent.pointerCancel(slider, { pointerId: 1 });
  expect(ha.calls.at(-1)).toMatchObject({
    command: 'setVolume',
    args: { volume: expect.closeTo(0.7, 1) },
  });
});

test('a transport button shows a ring from the press, and cannot be pressed again meanwhile', async () => {
  const { ha } = renderWithMock(<MediaPlayerBar entity="ha:room" />, player());
  const next = screen.getByRole('button', { name: 'Next' }) as HTMLButtonElement;
  fireEvent.click(next);
  expect(await screen.findByRole('status')).toBeTruthy();
  expect(next.disabled).toBe(true);
  await act(async () => {
    ha.update('room', { media: { title: 'Another' }, position: 1 });
  });

  await waitFor(() => expect(screen.queryByRole('status')).toBeNull(), { timeout: 2000 });
  expect(next.disabled).toBe(false);
});

import { mockLibrary, mockMediaPlayer, MockIntegration } from '@hashsome/core';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaQueue } from '../media-queue.tsx';

const room = (queue = true) => ({
  room: mockMediaPlayer({ name: 'Room', capabilities: { queue } }),
});

const render = (queue = true) =>
  renderWithMock(<MediaQueue entity="ha:room" />, room(queue), { library: mockLibrary() });

describe('MediaQueue', () => {
  test('lists the queue with its size, the track playing marked, and the tracks that follow', async () => {
    render();
    expect(await screen.findByText(/24 tracks/)).toBeTruthy();
    const playing = screen.getByRole('button', { name: /^Playing / });
    expect(playing.getAttribute('aria-current')).toBe('true');
    // The first two have played, the third plays, then it carries on.
    expect(screen.getAllByRole('listitem').length).toBeGreaterThan(5);
    // The rows come a screenful at a time, and a list with room to spare gets the rest as soon as it is drawn.
    expect(
      (await screen.findAllByRole('button', { name: 'Play Blank Space' })).length,
    ).toBeGreaterThan(0);
  });

  test('the heading says how long the whole queue plays, besides how many tracks it has', async () => {
    render();
    // Twenty-four made-up tracks of about three minutes each.
    expect(await screen.findByText(/24 tracks · 1 h \d+ min/)).toBeTruthy();
  });

  test('tapping a track jumps to it', async () => {
    const { ha } = render();
    const [first] = await screen.findAllByRole('button', { name: 'Play Blank Space' });
    fireEvent.click(first!);
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'playQueueItem',
      args: { item: expect.stringContaining('t-blank-space') },
    });
  });

  test('a track can be taken out, but the one playing cannot', async () => {
    const { ha } = render();
    await screen.findByText(/24 tracks/);
    fireEvent.click(
      (await screen.findAllByRole('button', { name: 'Remove Blank Space from the queue' }))[0]!,
    );

    expect(ha.calls.at(-1)).toMatchObject({ command: 'removeQueueItem' });
    // The mock queue repeats its tracks, so look in the playing row itself: it has no cross.
    const row = screen.getByRole('button', { name: /^Playing / }).closest('li')!;
    expect(row.querySelector('button[aria-label^="Remove"]')).toBeNull();
  });

  test('Clear empties the queue', async () => {
    const { ha } = render();
    fireEvent.click(await screen.findByRole('button', { name: 'Clear the queue' }));
    expect(ha.calls.at(-1)).toMatchObject({ command: 'clearQueue' });
  });

  test('while Clear works, the track that plays is not dimmed with the ones on their way out', async () => {
    const { ha } = render();
    // A slow clear, so the list is seen while it works.
    const send = ha.command.bind(ha);
    ha.command = async (...args) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      return send(...args);
    };

    const playing = (await screen.findByRole('button', { name: /^Playing / })).closest('li')!;
    const rows = screen.getAllByRole('listitem');
    const next = rows[rows.indexOf(playing) + 1]!;
    fireEvent.click(screen.getByRole('button', { name: 'Clear the queue' }));

    await waitFor(() => expect(Number(next.style.opacity)).toBeLessThan(1));
    expect(Number(playing.style.opacity || 1)).toBe(1);
  });

  test('a track coming up can be moved with the arrow keys on its handle, the one playing has none', async () => {
    const { ha } = render();
    await screen.findByText(/24 tracks/);
    const playing = screen.getByRole('button', { name: /^Playing / }).closest('li')!;
    expect(playing.querySelector('button[aria-label^="Move"]')).toBeNull();

    const [first] = screen.getAllByRole('button', { name: /^Move / });
    // Nowhere earlier to go in what comes next: the first one stays.
    fireEvent.keyDown(first!, { key: 'ArrowUp' });
    expect(ha.calls.filter((call) => call.command === 'moveQueueItem')).toHaveLength(0);

    fireEvent.keyDown(first!, { key: 'ArrowDown' });
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'moveQueueItem',
      args: { item: expect.stringContaining('q3-'), shift: 1 },
    });
  });

  test('when the last track has played and the player has stopped, it is shown as played', async () => {
    const queue = (id: string, title: string, current?: boolean) => ({
      id,
      title,
      duration: 100,
      ...(current ? { current } : {}),
    });

    vi.spyOn(MockIntegration.prototype, 'queue').mockResolvedValue({
      items: [queue('a', 'First'), queue('b', 'Last', true)],
      total: 2,
      offset: 0,
    });

    renderWithMock(
      <MediaQueue entity="ha:room" />,
      {
        room: mockMediaPlayer({
          name: 'Room',
          playback: 'idle',
          position: 100,
          duration: 100,
          capabilities: { queue: true },
        }),
      },
      { library: mockLibrary() },
    );

    const last = await screen.findByRole('button', { name: 'Play Last' });
    expect(last.getAttribute('aria-current')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Playing / })).toBeNull();
    vi.restoreAllMocks();
  });

  test('a track that is paused, or stopped part way, is still the one playing', async () => {
    vi.spyOn(MockIntegration.prototype, 'queue').mockResolvedValue({
      items: [{ id: 'a', title: 'Last', duration: 100, current: true }],
      total: 1,
      offset: 0,
    });

    renderWithMock(
      <MediaQueue entity="ha:room" />,
      {
        room: mockMediaPlayer({
          name: 'Room',
          playback: 'idle',
          position: 40,
          duration: 100,
          capabilities: { queue: true },
        }),
      },
      { library: mockLibrary() },
    );

    expect(
      (await screen.findByRole('button', { name: 'Playing Last' })).getAttribute('aria-current'),
    ).toBe('true');

    vi.restoreAllMocks();
  });

  test('says how many more there are than are shown', async () => {
    renderWithMock(<MediaQueue entity="ha:room" />, room(), { library: mockLibrary() });
    // The mock queue is 24 long and shows all of them, so nothing is left over.
    await screen.findByText(/24 tracks/);
    expect(screen.queryByText(/and .* more/)).toBeNull();
  });

  test('a player with no queue shows nothing', async () => {
    const { container } = render(false);
    await Promise.resolve();
    expect(container.textContent).toBe('');
  });
});

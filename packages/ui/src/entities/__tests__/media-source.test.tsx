import { mockMediaPlayer } from '@hashsome/core';
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaPlayerColumn } from '../media-player-column.tsx';
import { MediaQueue } from '../media-queue.tsx';
import { sourceLine } from '../media-source.ts';

const player = (extra: Record<string, unknown> = {}) => ({
  porch: mockMediaPlayer({
    name: 'Porch',
    playback: 'playing',
    media: { title: 'Dreams', artist: 'Fleetwood Mac', album: 'Rumours' },
    capabilities: { queue: true },
    ...extra,
  }),
});

test('the player does not say where it plays from under the artist', async () => {
  renderWithMock(
    <MediaPlayerColumn entity="ha:porch" />,
    player({ source: { id: 'pl-1', title: 'Daily Mix 2', kind: 'playlist' } }),
  );

  expect(await screen.findByText('Fleetwood Mac · Rumours')).toBeTruthy();
  expect(screen.queryByText(/Daily Mix 2/)).toBeNull();
});

test('the queue says what it was filled from, under its heading', async () => {
  renderWithMock(
    <MediaQueue entity="ha:porch" />,
    player({ source: { id: 'pl-1', title: 'Daily Mix 2', kind: 'playlist' } }),
  );

  expect(await screen.findByText('Playlist · Daily Mix 2')).toBeTruthy();
});

test('with no source, the queue says nothing of the kind', async () => {
  renderWithMock(<MediaQueue entity="ha:porch" />, player());
  expect(await screen.findByRole('heading', { name: /Queue/ })).toBeTruthy();
  expect(screen.queryByText(/Playlist ·/)).toBeNull();
});

test('the words for each kind of source', () => {
  expect(sourceLine({ id: 'x', title: 'Daily Mix 2', kind: 'playlist' })).toBe(
    'Playlist · Daily Mix 2',
  );

  expect(sourceLine({ id: 'x', title: 'Rumours', kind: 'album' })).toBe('Album · Rumours');
  expect(sourceLine({ id: 'x', title: 'Hessian', kind: 'artist' })).toBe('Artist · Hessian');
  expect(sourceLine({ id: 'x', title: 'BBC 6', kind: 'radio' })).toBe('Radio station · BBC 6');
  expect(sourceLine({ id: 'x', title: 'Mix' })).toBe('Mix');
});

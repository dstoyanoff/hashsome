import { mockMediaPlayer } from '@hashsome/core';
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaPlayerColumn } from '../media-player-column.tsx';
import { sourceLabel, sourceParts } from '../now-playing.tsx';

const player = (extra: Record<string, unknown> = {}) => ({
  porch: mockMediaPlayer({
    name: 'Porch',
    playback: 'playing',
    media: { title: 'Dreams', artist: 'Fleetwood Mac', album: 'Rumours' },
    ...extra,
  }),
});

test('the player says which playlist it plays from, under the artist', async () => {
  renderWithMock(
    <MediaPlayerColumn entity="ha:porch" />,
    player({ source: { title: 'Daily Mix 2', kind: 'playlist' } }),
  );

  // "from playlist" and the playlist's name are two pieces, the name set apart from the rest.
  expect(await screen.findByText('from playlist')).toBeTruthy();
  expect(screen.getByText('Daily Mix 2')).toBeTruthy();
  expect(screen.getByText('Fleetwood Mac · Rumours')).toBeTruthy();
});

test('with no source, nothing is said', async () => {
  renderWithMock(<MediaPlayerColumn entity="ha:porch" />, player());
  expect(await screen.findByText('Fleetwood Mac · Rumours')).toBeTruthy();
  expect(screen.queryByText(/^from /)).toBeNull();
});

test('the words for each kind of source', () => {
  expect(sourceLabel({ title: 'Rumours', kind: 'album' })).toBe('from album Rumours');
  expect(sourceLabel({ title: 'Hessian', kind: 'artist' })).toBe('from artist Hessian');
  expect(sourceLabel({ title: 'BBC 6', kind: 'radio' })).toBe('from radio station BBC 6');
  expect(sourceLabel({ title: 'Mix' })).toBe('from Mix');
});

test('the line is made of a lowercase lead and the name', () => {
  expect(sourceParts({ title: 'Daily Mix 2', kind: 'playlist' })).toEqual({
    lead: 'from playlist',
    title: 'Daily Mix 2',
  });

  expect(sourceParts({ title: 'Mix' })).toEqual({ lead: 'from', title: 'Mix' });
});

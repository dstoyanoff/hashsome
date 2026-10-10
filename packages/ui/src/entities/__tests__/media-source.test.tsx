import { mockMediaPlayer } from '@hashsome/core';
import { screen } from '@testing-library/react';
import { expect, test } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaPlayerColumn } from '../media-player-column.tsx';
import { sourceLabel } from '../now-playing.tsx';

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

  expect(await screen.findByText('From playlist Daily Mix 2')).toBeTruthy();
  expect(screen.getByText('Fleetwood Mac · Rumours')).toBeTruthy();
});

test('with no source, nothing is said', async () => {
  renderWithMock(<MediaPlayerColumn entity="ha:porch" />, player());
  expect(await screen.findByText('Fleetwood Mac · Rumours')).toBeTruthy();
  expect(screen.queryByText(/^From /)).toBeNull();
});

test('the words for each kind of source', () => {
  expect(sourceLabel({ title: 'Rumours', kind: 'album' })).toBe('From album Rumours');
  expect(sourceLabel({ title: 'Hessian', kind: 'artist' })).toBe('From artist Hessian');
  expect(sourceLabel({ title: 'BBC 6', kind: 'radio' })).toBe('From radio station BBC 6');
  expect(sourceLabel({ title: 'Mix' })).toBe('From Mix');
});

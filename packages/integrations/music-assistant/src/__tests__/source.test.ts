import { expect, test } from 'vitest';
import { toBrowseItem } from '../browse.ts';
import { ownPicturePath, sourceOf } from '../mapper.ts';

const entry = (media_type: string, name: string) => ({
  media_type,
  name,
  uri: `library://${media_type}/1`,
});

test('a queue filled from a playlist, an album, an artist or a station says which', () => {
  expect(sourceOf({ sources: [entry('playlist', 'Daily Mix 2')] })).toEqual({
    title: 'Daily Mix 2',
    kind: 'playlist',
  });

  expect(sourceOf({ sources: [entry('album', 'Colours of the Sky')] })).toEqual({
    title: 'Colours of the Sky',
    kind: 'album',
  });

  expect(sourceOf({ sources: [entry('artist', 'Hessian')] })).toMatchObject({ kind: 'artist' });
  expect(sourceOf({ sources: [entry('radio', 'BBC 6')] })).toMatchObject({ kind: 'radio' });
});

test('a track put in on its own, or nothing at all, is not a source', () => {
  expect(sourceOf({ sources: [entry('track', 'Dreams')] })).toBeUndefined();
  expect(sourceOf({ sources: [] })).toBeUndefined();
  expect(sourceOf({})).toBeUndefined();
  expect(
    sourceOf({ sources: [null, { media_type: 'playlist' }, entry('playlist', '')] }),
  ).toBeUndefined();
});

test('the first source that is one wins', () => {
  expect(
    sourceOf({
      sources: [entry('track', 'Dreams'), entry('playlist', 'Roadtrip'), entry('album', 'X')],
    }),
  ).toEqual({ title: 'Roadtrip', kind: 'playlist' });
});

test('a picture Music Assistant serves itself has a path, one from elsewhere does not', () => {
  // At the size the displays use, not the 512 it gave.
  expect(ownPicturePath('http://10.4.2.2/imageproxy/abc123?size=512&fmt=jpg')).toBe(
    '/imageproxy/abc123?size=256&fmt=jpg',
  );

  expect(ownPicturePath('https://i.scdn.co/image/ab67')).toBeUndefined();
  expect(ownPicturePath('not a url')).toBeUndefined();
});

test('a library item’s picture goes through the runtime when it has Music Assistant’s id for it', () => {
  const assetUrl = (path: string) => `/asset?path=${encodeURIComponent(path)}`;
  const item = {
    uri: 'library://album/1',
    name: 'Album',
    media_type: 'album',
    image: { path: 'https://i.scdn.co/image/xyz', remotely_accessible: true, proxy_id: 'abc' },
  };

  expect(toBrowseItem(item, assetUrl)).toMatchObject({
    artworkUrl: '/asset?path=%2Fimageproxy%2Fabc%3Fsize%3D256%26fmt%3Djpg',
  });

  // With no way to address the runtime, or no id, the picture's own address, as before.
  expect(toBrowseItem(item)).toMatchObject({ artworkUrl: 'https://i.scdn.co/image/xyz' });
  expect(
    toBrowseItem({ ...item, image: { path: '/local', remotely_accessible: false } }, assetUrl),
  ).not.toHaveProperty('artworkUrl');
});

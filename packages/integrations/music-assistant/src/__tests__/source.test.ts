import { expect, test } from 'vitest';
import { sourceOf } from '../mapper.ts';

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

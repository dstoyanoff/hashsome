import type { BrowseItem } from '@hashsome/core';
import { expect, test } from 'vitest';
import { rankSearchResults } from '../use-media-browser.ts';

const item = (id: string, title: string): BrowseItem => ({
  id,
  title,
  kind: 'playlist',
  playable: true,
  expandable: true,
});

const titles = (items: BrowseItem[]) => items.map((entry) => entry.title);

test('an exact match comes first, then titles that start with it, then a word, then anywhere', () => {
  const ranked = rankSearchResults(
    [
      item('1', 'Chill Music Mix'),
      item('2', 'Greatest Cdeep Music Hits'),
      item('3', 'Cdeep Music Radio'),
      item('4', 'Cdeep Music'),
      item('5', 'The Best Of'),
    ],
    'cdeep music',
  );

  expect(titles(ranked)).toEqual([
    'Cdeep Music',
    'Cdeep Music Radio',
    'Greatest Cdeep Music Hits',
    'Chill Music Mix',
    'The Best Of',
  ]);
});

test('matching ignores case and keeps the library order among equal matches', () => {
  const ranked = rankSearchResults(
    [item('a', 'Zeta'), item('b', 'Alpha'), item('c', 'ALPHA')],
    'alpha',
  );

  expect(ranked.map((entry) => entry.id)).toEqual(['b', 'c', 'a']);
});

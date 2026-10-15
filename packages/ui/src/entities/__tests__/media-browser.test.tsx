import { mockLibrary, mockMediaPlayer, type MockIntegration } from '@hashsome/core';
import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, test, vi } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { MediaBrowser } from '../media-browser.tsx';

const rooms = {
  room: mockMediaPlayer({
    name: 'Room',
    capabilities: { browse: true, search: true },
  }),
};

const queueRoom = () => ({
  room: mockMediaPlayer({
    name: 'Room',
    capabilities: { browse: true, search: true, queue: true },
  }),
});

const render = (entities = rooms) =>
  renderWithMock(<MediaBrowser entity="ha:room" />, entities, { library: mockLibrary() });

test('the top level is a row of tabs, opening on the first, and no list of folders', async () => {
  render();
  const tabs = await screen.findAllByRole('tab');
  expect(tabs.map((tab) => tab.textContent)).toEqual([
    'Recently played',
    'Playlists',
    'Albums',
    'Artists',
    'Radio',
  ]);

  expect(tabs[0]!.getAttribute('aria-selected')).toBe('true');
  // It opens straight onto the first shelf, so the first thing shown is music, not folders.
  expect(await screen.findByRole('button', { name: 'Play Dreams' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Open Albums' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
});

test('opening something changes only what is below the tabs, and the selected tab becomes the way back', async () => {
  render();
  fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
  expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Open Rumours' }));
  expect(await screen.findByRole('button', { name: 'Play Go Your Own Way' })).toBeTruthy();
  // Search and tabs stay put; the selected tab now reads as the way back, named for what is open.
  expect(screen.getByRole('searchbox', { name: 'Search the library' })).toBeTruthy();
  expect(screen.getAllByRole('tab')).toHaveLength(5);
  const back = screen.getByRole('tab', { name: 'Back' });
  expect(back.getAttribute('aria-selected')).toBe('true');
  expect(back.textContent).toBe('Rumours');
  // No separate heading or back row is added under the tabs.
  expect(screen.queryByRole('heading')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Back' })).toBeNull();
  fireEvent.click(back);
  expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
  expect(screen.getByRole('tab', { name: 'Albums' }).getAttribute('aria-selected')).toBe('true');
});

test('opening an album adds its actions to the row of tabs and inserts nothing, so nothing moves', async () => {
  renderWithMock(<MediaBrowser entity="ha:room" />, queueRoom(), { library: mockLibrary() });
  fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
  const list = async () => (await screen.findAllByRole('list')).at(-1) as HTMLElement;
  // The one element between the search box and the list: the tabs, and the album's actions at their end.
  const row = (el: HTMLElement) => el.previousElementSibling as HTMLElement;
  const before = row(await list());
  expect(before.contains(screen.getByRole('tablist'))).toBe(true);
  expect(screen.queryByRole('button', { name: 'Play' })).toBeNull();

  fireEvent.click(screen.getByRole('button', { name: 'Open Rumours' }));
  await screen.findByRole('button', { name: 'Play Go Your Own Way' });
  const after = row(await list());
  // The same row, now with the album's actions in it; no extra row came between.
  expect(after).toBe(before);
  expect(after.contains(screen.getByRole('button', { name: 'Play' }))).toBe(true);
  expect(after.contains(screen.getByRole('button', { name: 'Shuffle' }))).toBe(true);
  expect(after.contains(screen.getByRole('tablist'))).toBe(true);
});

test('picking another tab from inside a shelf leaves what was open', async () => {
  render();
  fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Open Rumours' }));
  await screen.findByRole('button', { name: 'Play Go Your Own Way' });
  fireEvent.click(screen.getByRole('tab', { name: 'Playlists' }));
  expect(await screen.findByRole('button', { name: 'Open Morning coffee' })).toBeTruthy();
  expect(screen.getByRole('tab', { name: 'Playlists' }).getAttribute('aria-selected')).toBe('true');
  expect(screen.queryByRole('tab', { name: 'Back' })).toBeNull();
  expect(screen.queryByText('Rumours')).toBeNull();
});

test('a top level that is not just folders stays a plain list under a heading', async () => {
  renderWithMock(<MediaBrowser entity="ha:room" />, rooms, {
    library: {
      root: {
        items: [
          { id: 'f', title: 'Local media', kind: 'folder', playable: false, expandable: true },
          { id: 's', title: 'Lofi Beats', kind: 'radio', playable: true, expandable: false },
        ],
      },
    },
  });

  expect(await screen.findByRole('button', { name: 'Open Local media' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Play Lofi Beats' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: 'Library' })).toBeTruthy();
  expect(screen.queryByRole('tab')).toBeNull();
});

test('a single folder is not worth a tab bar', async () => {
  renderWithMock(<MediaBrowser entity="ha:room" />, rooms, {
    library: {
      root: {
        items: [{ id: 'f', title: 'Only', kind: 'folder', playable: false, expandable: true }],
      },
    },
  });

  expect(await screen.findByRole('button', { name: 'Open Only' })).toBeTruthy();
  expect(screen.queryByRole('tab')).toBeNull();
});

test('a track plays on a tap, and an album has its own play button beside it', async () => {
  const { ha } = render();
  fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Play 1989' }));
  expect(ha.calls.at(-1)).toMatchObject({
    entityId: 'room',
    command: 'playMedia',
    args: { item: 'al-1989' },
  });

  fireEvent.click(screen.getByRole('button', { name: 'Open 1989' }));
  fireEvent.click(await screen.findByRole('button', { name: 'Play Style' }));
  expect(ha.calls.at(-1)).toMatchObject({ command: 'playMedia', args: { item: 't-style' } });
});

test('the search box shows only for a library that can be searched, and lists matches', async () => {
  const { unmount } = render({
    room: mockMediaPlayer({ name: 'Room', capabilities: { browse: true, search: false } }),
  });

  await screen.findByRole('tab', { name: 'Albums' });
  expect(screen.queryByRole('searchbox', { name: 'Search the library' })).toBeNull();
  unmount();

  render();
  await screen.findByRole('tab', { name: 'Albums' });
  const box = screen.getByRole('searchbox', { name: 'Search the library' });
  act(() => {
    fireEvent.change(box, { target: { value: 'dream' } });
  });

  expect(await screen.findByRole('button', { name: 'Play Dreams' })).toBeTruthy();
  // The shelves give way to the kinds found, since results belong to no shelf.
  expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
  expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['All', 'Tracks']);
  expect(screen.getByRole('tab', { name: 'All' }).getAttribute('aria-selected')).toBe('true');

  act(() => {
    fireEvent.change(box, { target: { value: 'zzzz' } });
  });

  expect(await screen.findByText('Nothing found.')).toBeTruthy();
  // The clear button empties the search and returns to the shelf.
  fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));
  // The search box empties and the tabs come back.
  expect(await screen.findByRole('tab', { name: 'Albums' })).toBeTruthy();
  expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('');
  expect(screen.getByRole('tab', { name: 'Recently played' }).getAttribute('aria-selected')).toBe(
    'true',
  );
});

test('the browser’s own clear button is not drawn in the search field, which has one of its own', async () => {
  render();
  await screen.findByRole('tab', { name: 'Albums' });
  act(() => {
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'dream' } });
  });

  await screen.findByRole('button', { name: 'Clear search' });
  const styles = [...document.querySelectorAll('style')].map((sheet) => sheet.textContent).join('');
  expect(styles).toMatch(/::-webkit-search-cancel-button[^{]*\{[^}]*display:\s*none/);
});

test('a search that found one kind can be narrowed to it too, which asks the library for more of it', async () => {
  const { ha } = render();
  await screen.findByRole('tab', { name: 'Albums' });
  const browse = vi.spyOn(ha, 'browse');
  act(() => {
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'dream' } });
  });

  fireEvent.click(await screen.findByRole('tab', { name: 'Tracks' }));
  expect(await screen.findByRole('button', { name: 'Play Dreams' })).toBeTruthy();
  await waitFor(() =>
    expect(browse).toHaveBeenCalledWith(expect.anything(), { search: 'dream', kind: 'track' }),
  );
});

test('opening an album from search results opens it, and going back returns to the results', async () => {
  render();
  await screen.findByRole('tab', { name: 'Albums' });
  act(() => {
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'rumours' } });
  });

  fireEvent.click(await screen.findByRole('button', { name: 'Open Rumours' }));
  // Its tracks are listed, the search is out of the way, and the open album is what a "play all" plays.
  expect(await screen.findByRole('button', { name: 'Play Go Your Own Way' })).toBeTruthy();
  expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('');
  expect(screen.getByRole('button', { name: 'Play' })).toBeTruthy();

  // Back is to the results it was opened from, with what was typed still there.
  fireEvent.click(screen.getByRole('tab', { name: 'Back' }));
  expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
  expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('rumours');
  expect(screen.queryByRole('button', { name: 'Play Go Your Own Way' })).toBeNull();
});

describe('narrowing a search to one kind of result', () => {
  const search = async (text: string) => {
    const view = render();
    await screen.findByRole('tab', { name: 'Albums' });
    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: text } });
    });

    return view;
  };

  test('a search that found several kinds shows them in place of the shelves, with All selected', async () => {
    await search('fleetwood');
    // The shelves have an "Artists" tab too: wait for the kinds to replace them.
    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'All',
      'Tracks',
      'Albums',
      'Artists',
    ]);

    expect(screen.getByRole('tab', { name: 'All' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByRole('tab', { name: 'Recently played' })).toBeNull();
    // The mixed list: an artist and a track are both there.
    expect(screen.getByRole('button', { name: 'Open Fleetwood Mac' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Play Dreams' })).toBeTruthy();
  });

  test('choosing a kind lists only that kind, asking the library for it, and All goes back', async () => {
    const { ha } = await search('fleetwood');
    await screen.findByRole('tab', { name: 'All' });
    fireEvent.click(screen.getByRole('tab', { name: 'Artists' }));
    expect(await screen.findByRole('button', { name: 'Open Fleetwood Mac' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Play Dreams' })).toBeNull();
    expect(screen.getByRole('tab', { name: 'Artists' }).getAttribute('aria-selected')).toBe('true');
    // The kinds stay to choose from, not only the one chosen.
    expect(screen.getByRole('tab', { name: 'Tracks' })).toBeTruthy();

    fireEvent.click(screen.getByRole('tab', { name: 'All' }));
    expect(await screen.findByRole('button', { name: 'Play Dreams' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'All' }).getAttribute('aria-selected')).toBe('true');
    expect(ha.calls).toEqual([]);
  });

  test('the shelves are only for a search with no results, and clearing the search brings them back', async () => {
    await search('zzzz');
    expect(await screen.findByText('Nothing found.')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Albums' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: 'All' })).toBeNull();

    cleanup();
    await search('fleetwood');
    await screen.findByRole('tab', { name: 'All' });
    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(await screen.findByRole('tab', { name: 'Recently played' })).toBeTruthy();
    expect(screen.queryByRole('tab', { name: 'All' })).toBeNull();
  });

  test('opening a result with a kind chosen, and going back, returns to that kind', async () => {
    await search('fleetwood');
    await screen.findByRole('tab', { name: 'All' });
    fireEvent.click(screen.getByRole('tab', { name: 'Albums' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Play Dreams' })).toBeNull());
    fireEvent.click(await screen.findByRole('button', { name: 'Open Rumours' }));
    expect(await screen.findByRole('button', { name: 'Play Go Your Own Way' })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'Back' }));
    expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
    expect((screen.getByRole('searchbox') as HTMLInputElement).value).toBe('fleetwood');
    expect(screen.getByRole('tab', { name: 'Albums' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByRole('button', { name: 'Open Fleetwood Mac' })).toBeNull();
  });
});

describe('while a search is on its way', () => {
  /** Holds back the answer to a search until `release` is called, as a slow library does. */
  const slow = (ha: MockIntegration) => {
    const original = ha.browse.bind(ha);
    const held: (() => void)[] = [];
    vi.spyOn(ha, 'browse').mockImplementation((entityId, query) =>
      query.search === undefined
        ? original(entityId, query)
        : new Promise((resolve) => held.push(() => resolve(original(entityId, query)))),
    );

    return () => held.splice(0).forEach((release) => release());
  };

  test('the kinds are a placeholder, not the shelves, until the first answer', async () => {
    const { ha } = render();
    await screen.findByRole('tab', { name: 'Albums' });
    const release = slow(ha);
    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'fleetwood' } });
    });

    await waitFor(() => expect(screen.queryByRole('tab')).toBeNull());
    expect(document.querySelector('[aria-busy="true"]')).not.toBeNull();
    // Past the pause before the search is made, with its answer held back, it is still the placeholder.
    await act(async () => new Promise((resolve) => setTimeout(resolve, 450)));
    expect(screen.queryByRole('tab')).toBeNull();
    await act(async () => release());
    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
    expect(document.querySelector('[aria-busy="true"]')).toBeNull();
  });

  test('typing on keeps the kinds already found until the new ones arrive, instead of showing the shelves', async () => {
    const { ha } = render();
    await screen.findByRole('tab', { name: 'Albums' });
    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'fleetwood' } });
    });

    await screen.findByRole('tab', { name: 'All' });
    const release = slow(ha);
    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'fleetwood m' } });
    });

    // Past the pause before a search is made, with its answer still held back.
    await act(async () => new Promise((resolve) => setTimeout(resolve, 450)));
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'All',
      'Tracks',
      'Albums',
      'Artists',
    ]);

    await act(async () => release());
    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
  });
});

test('a library that cannot be read shows the reason', async () => {
  renderWithMock(<MediaBrowser entity="ha:room" />, rooms);
  await waitFor(() => expect(screen.getByRole('alert').textContent).toMatch(/Nothing at/));
});

describe('layouts', () => {
  const rows = () => screen.getAllByRole('list').at(-1) as HTMLElement;
  const show = (layout?: 'list' | 'theater' | 'auto') =>
    renderWithMock(<MediaBrowser entity="ha:room" {...(layout ? { layout } : {})} />, rooms, {
      library: mockLibrary(),
    });

  test('the default is a column of rows', async () => {
    show();
    await screen.findByRole('button', { name: 'Play Dreams' });
    expect(getComputedStyle(rows()).flexDirection).toBe('column');
  });

  test('theater is a single row of cards that scrolls sideways', async () => {
    show('theater');
    await screen.findByRole('button', { name: 'Play Dreams' });
    const row = rows();
    expect(getComputedStyle(row).flexDirection).not.toBe('column');
    expect(getComputedStyle(row).overflowX).toBe('auto');
    // The same items, opened and played the same way.
    fireEvent.click(screen.getByRole('tab', { name: 'Albums' }));
    expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Play Rumours' }));
  });

  test.each(['list', 'theater'] as const)(
    'a long title is held to its room and rolls (or ends in an ellipsis) instead of running over the next item (%s)',
    async (layout) => {
      show(layout);
      await screen.findByRole('button', { name: 'Play Dreams' });
      // The title sits in a frame that clips it to the room it has.
      const title = (screen.getAllByText('Dreams')[0] as HTMLElement).parentElement as HTMLElement;
      const style = getComputedStyle(title);
      expect(style.whiteSpace).toBe('nowrap');
      expect(style.textOverflow).toBe('ellipsis');
      expect(style.overflow).toBe('hidden');
    },
  );

  test('the search keeps its height when the list takes all the room', async () => {
    show();
    await screen.findByRole('button', { name: 'Play Dreams' });
    const search = screen.getByRole('searchbox', { name: 'Search the library' }).parentElement!;
    expect(getComputedStyle(search).flexShrink).toBe('0');
    // The list is what gives way: it scrolls inside the room it has.
    expect(getComputedStyle(rows()).overflowY).toBe('auto');
  });

  test('a row’s play button is not squeezed by a long title', async () => {
    show();
    fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
    // An album can be opened and played, so it has a play button of its own beside the title.
    const play = await screen.findByRole('button', { name: 'Play Rumours' });
    expect(getComputedStyle(play).flexShrink).toBe('0');
  });

  test('a theater card is large, an artist’s is round, and its play button sits over the artwork', async () => {
    const { ha } = show('theater');
    fireEvent.click(await screen.findByRole('tab', { name: 'Artists' }));
    const artist = await screen.findByRole('button', { name: 'Open Fleetwood Mac' });
    const artwork = artist.querySelector('img')!.parentElement as HTMLElement;
    // Up to 200px, and less only when the row is too short for that (a container query, not measured in jsdom).
    expect(getComputedStyle(artwork).width).toMatch(/^min\(200px,/);
    expect(getComputedStyle(artwork).borderRadius).toBe('999px');
    fireEvent.click(screen.getByRole('button', { name: 'Play Fleetwood Mac' }));
    expect(ha.calls.at(-1)).toMatchObject({ command: 'playMedia', args: { item: 'ar-mac' } });

    fireEvent.click(screen.getByRole('tab', { name: 'Albums' }));
    const album = await screen.findByRole('button', { name: 'Open Rumours' });
    expect(
      getComputedStyle(album.querySelector('img')!.parentElement as HTMLElement).borderRadius,
    ).not.toBe('999px');
  });

  test('a card with no artwork shows the kind’s icon, large', async () => {
    renderWithMock(<MediaBrowser entity="ha:room" layout="theater" />, rooms, {
      library: {
        root: {
          items: [
            { id: 'r', title: 'Lofi Beats', kind: 'radio', playable: true, expandable: false },
          ],
        },
      },
    });

    const card = await screen.findByRole('button', { name: 'Play Lofi Beats' });
    expect(card.querySelector('img')).toBeNull();
    expect(card.querySelector('svg')).not.toBeNull();
  });
});

describe('playing, queueing and the queue', () => {
  const queueRooms = {
    room: mockMediaPlayer({
      name: 'Room',
      capabilities: { browse: true, search: true, queue: true },
    }),
  };

  const openRumours = async (entities = queueRooms) => {
    const view = render(entities);
    fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Open Rumours' }));
    await screen.findByRole('button', { name: 'Play Go Your Own Way' });
    return view;
  };

  test('a track in an album plays the whole album from there, replacing the queue', async () => {
    const { ha } = await openRumours();
    fireEvent.click(screen.getByRole('button', { name: 'Play Go Your Own Way' }));
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'playMedia',
      args: { item: 't-go-your-own-way', mode: 'replace', context: 'al-rumours' },
    });
  });

  test('a track on a shelf plays on its own, replacing the queue, with no album around it', async () => {
    const { ha } = render(queueRooms);
    fireEvent.click(await screen.findByRole('button', { name: 'Play Dreams' }));
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'playMedia',
      args: { item: 't-dreams', mode: 'replace' },
    });

    expect(ha.calls.at(-1)!.args).not.toHaveProperty('context');
  });

  test('an open album can be played, shuffled or added whole, from its own buttons', async () => {
    const { ha } = await openRumours();
    fireEvent.click(screen.getByRole('button', { name: 'Play' }));
    expect(ha.calls.at(-1)).toMatchObject({
      command: 'playMedia',
      args: { item: 'al-rumours', mode: 'replace' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Shuffle' }));
    expect(ha.calls.at(-1)).toMatchObject({
      args: { item: 'al-rumours', mode: 'replace', shuffle: true },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Add to queue' }));
    expect(ha.calls.at(-1)).toMatchObject({ args: { item: 'al-rumours', mode: 'add' } });
  });

  test('every track can be played next or added to the queue', async () => {
    const { ha } = await openRumours();
    fireEvent.click(screen.getByRole('button', { name: 'Play Go Your Own Way next' }));
    expect(ha.calls.at(-1)).toMatchObject({
      args: { item: 't-go-your-own-way', mode: 'next' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Add Go Your Own Way to the queue' }));
    expect(ha.calls.at(-1)).toMatchObject({
      args: { item: 't-go-your-own-way', mode: 'add' },
    });
  });

  test('a player with no queue can only play: no queue buttons, and no shuffle or add for an album', async () => {
    await openRumours(rooms);
    expect(screen.getByRole('button', { name: 'Play' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Shuffle' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add to queue' })).toBeNull();
    expect(screen.queryByRole('button', { name: /next$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /to the queue$/ })).toBeNull();
  });
});

test('every tab is the same height, whatever its label holds, so opening a playlist with an emoji in its name moves nothing', async () => {
  render();
  fireEvent.click(await screen.findByRole('tab', { name: 'Playlists' }));
  const heights = () => screen.getAllByRole('tab').map((tab) => getComputedStyle(tab).height);

  const before = heights();
  expect(new Set(before)).toEqual(new Set(['28px']));

  // The playlist with an emoji in its name: its title becomes the selected tab.
  fireEvent.click(await screen.findByRole('button', { name: /^Open BASS BOOSTED SONGS/ }));
  await screen.findByRole('button', { name: 'Play Electric Feel' });
  expect(screen.getByRole('tab', { name: 'Back' }).textContent).toContain('\u{1F50A}');
  expect(heights()).toEqual(before);
});

// `search="icon"`: the search is an icon at the end of the row of tabs, and the field takes the row's place.

const renderIcon = (entities = rooms) =>
  renderWithMock(<MediaBrowser entity="ha:room" search="icon" />, entities, {
    library: mockLibrary(),
  });

test('with search="icon" there is no field until the icon is pressed, and the icon is after the tabs', async () => {
  renderIcon();
  await screen.findByRole('tab', { name: 'Albums' });

  expect(screen.queryByRole('searchbox')).toBeNull();
  const icon = screen.getByRole('button', { name: 'Search' });
  // The tabs first, then the icon at the end of the same row.
  const tabs = screen.getByRole('tab', { name: 'Radio' });
  expect(tabs.compareDocumentPosition(icon) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(icon.parentElement?.parentElement?.contains(tabs)).toBe(true);
});

test('pressing the icon turns the row into the search field, which finds things and is closed by its button', async () => {
  renderIcon();
  await screen.findByRole('tab', { name: 'Albums' });

  fireEvent.click(screen.getByRole('button', { name: 'Search' }));
  const box = screen.getByRole('searchbox', { name: 'Search the library' });
  expect(document.activeElement).toBe(box);
  // The tabs and the icon give their place to the field (the row fades out as the field comes in).
  await waitFor(() => expect(screen.queryByRole('tab', { name: 'Albums' })).toBeNull());
  expect(screen.queryByRole('button', { name: 'Search' })).toBeNull();

  act(() => {
    fireEvent.change(box, { target: { value: 'dream' } });
  });

  expect(await screen.findByRole('button', { name: 'Play Dreams' })).toBeTruthy();

  fireEvent.click(screen.getByRole('button', { name: 'Close search' }));
  // The search is empty again and the tabs and the icon are back.
  expect(await screen.findByRole('tab', { name: 'Albums' })).toBeTruthy();
  await waitFor(() => expect(screen.queryByRole('searchbox')).toBeNull());
  expect(screen.getByRole('button', { name: 'Search' })).toBeTruthy();
});

test('Escape closes the search too, and a library that cannot be searched has no icon', async () => {
  const { unmount } = renderIcon();
  await screen.findByRole('tab', { name: 'Albums' });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));
  fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Escape' });
  expect(await screen.findByRole('tab', { name: 'Albums' })).toBeTruthy();
  unmount();

  renderIcon({
    room: mockMediaPlayer({ name: 'Room', capabilities: { browse: true, search: false } }),
  });

  await screen.findByRole('tab', { name: 'Albums' });
  expect(screen.queryByRole('button', { name: 'Search' })).toBeNull();
});

describe('a request the player has not answered yet', () => {
  const slow = async () => {
    const { ha } = render(queueRoom());
    let answer = () => {};
    const original = ha.command.bind(ha);
    vi.spyOn(ha, 'command').mockImplementation((...args) => {
      const done = original(...args);
      return new Promise((resolve) => {
        answer = () => resolve(done);
      });
    });

    return { ha, release: () => answer() };
  };

  test('shows a spinner on the item and the button asked, and clears when it answers', async () => {
    const { release } = await slow();
    const add = await screen.findByRole('button', { name: 'Add Dreams to the queue' });
    fireEvent.click(add);
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(true));
    // Only the same action is held; playing is still possible.
    expect(
      (screen.getByRole('button', { name: 'Play Dreams' }) as HTMLButtonElement).disabled,
    ).toBe(false);

    // Queueing changes nothing the player shows, so it clears when the wait for a sign of it runs out.
    await act(async () => release());
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false), {
      timeout: 4000,
    });
  });

  test('starting an item puts a spinner over its artwork until it answers', async () => {
    const { ha, release } = await slow();
    fireEvent.click(await screen.findByRole('button', { name: 'Play Dreams' }));
    expect(await screen.findAllByRole('status')).not.toHaveLength(0);
    // Answered, but not started: still waiting. It is done when the track plays and has moved on from 0:00.
    await act(async () => release());
    await act(async () => {
      ha.update('room', { playback: 'playing', media: { title: 'Dreams' }, position: 0 });
    });

    expect(screen.queryAllByRole('status')).not.toHaveLength(0);
    await act(async () => {
      ha.update('room', { playback: 'playing', media: { title: 'Dreams' }, position: 0.5 });
    });

    await waitFor(() => expect(screen.queryAllByRole('status')).toHaveLength(0), {
      timeout: 2000,
    });
  });
});

describe('what the player is playing', () => {
  const playing = (playback: 'playing' | 'paused' = 'playing') => ({
    room: mockMediaPlayer({
      name: 'Room',
      playback,
      media: { title: 'Dreams', artist: 'Fleetwood Mac' },
      source: { id: 'pl-morning', title: 'Morning coffee', kind: 'playlist' },
      capabilities: { browse: true, search: true, queue: true },
    }),
  });

  test("the playlist it plays from has the player's pause in place of its other actions", async () => {
    const { ha } = render(playing());
    fireEvent.click(await screen.findByRole('tab', { name: 'Playlists' }));
    expect(await screen.findByRole('button', { name: 'Pause Morning coffee' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Play Morning coffee next' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add Morning coffee to the queue' })).toBeNull();
    // The others keep theirs.
    expect(screen.getByRole('button', { name: 'Add Dinner party to the queue' })).toBeTruthy();

    const toggled = vi.spyOn(ha, 'command');
    fireEvent.click(screen.getByRole('button', { name: 'Pause Morning coffee' }));
    expect(toggled).toHaveBeenCalledWith('room', 'togglePlay', undefined);
  });

  test('a paused player offers to resume it', async () => {
    render(playing('paused'));
    fireEvent.click(await screen.findByRole('tab', { name: 'Playlists' }));
    expect(await screen.findByRole('button', { name: 'Resume Morning coffee' })).toBeTruthy();
  });

  test('the track playing is marked in a list, and plays and pauses instead of starting again', async () => {
    render(playing());
    expect(await screen.findByRole('button', { name: 'Pause Dreams' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add Dreams to the queue' })).toBeNull();
  });
});

describe('saying what each result is', () => {
  const find = async (text: string, search?: 'icon') => {
    renderWithMock(<MediaBrowser entity="ha:room" {...(search ? { search } : {})} />, rooms, {
      library: mockLibrary(),
    });

    await screen.findByRole('tab', { name: 'Albums' });
    if (search) {
      fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    }

    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: text } });
    });
  };

  test('the field opened from its icon takes focus, to type straight away', async () => {
    renderWithMock(<MediaBrowser entity="ha:room" search="icon" />, rooms, {
      library: mockLibrary(),
    });

    await screen.findByRole('tab', { name: 'Albums' });
    fireEvent.click(screen.getByRole('button', { name: 'Search' }));
    expect(document.activeElement).toBe(await screen.findByRole('searchbox'));
  });

  test('with the search opened from its icon, the kinds are under the field', async () => {
    await find('fleetwood', 'icon');
    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual([
      'All',
      'Tracks',
      'Albums',
      'Artists',
    ]);

    expect(screen.getByRole('searchbox')).toBeTruthy();
  });

  test('opening a result puts the field away so the way back shows, and going back returns to the phrase', async () => {
    await find('fleetwood', 'icon');
    fireEvent.click(await screen.findByRole('button', { name: 'Open Fleetwood Mac' }));

    // No blank field over the tabs: the selected tab is the way back, named for what is open.
    expect(await screen.findByRole('tab', { name: 'Back' })).toBeTruthy();
    await waitFor(() => expect(screen.queryByRole('searchbox')).toBeNull());

    // Nor is there a search icon in there to open an empty field: it comes back with the results.
    expect(screen.queryByRole('button', { name: 'Search' })).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: 'Back' }));
    const field = (await screen.findByRole('searchbox')) as HTMLInputElement;
    expect(field.value).toBe('fleetwood');
    // Back with the phrase in it, it does not take focus (and bring the keyboard up).
    expect(document.activeElement).not.toBe(field);
    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
  });

  test('in a list of several kinds, each result says which it is', async () => {
    await find('fleetwood');
    expect((await screen.findAllByText(/^Artist/)).length).toBeGreaterThan(0);
    expect(screen.getAllByText(/^Track · /).length).toBeGreaterThan(0);
  });

  test('narrowed to one kind, they do not repeat it', async () => {
    await find('fleetwood');
    fireEvent.click(await screen.findByRole('tab', { name: 'Tracks' }));
    await screen.findByRole('button', { name: 'Play Dreams' });
    expect(screen.queryByText(/^Track · /)).toBeNull();
  });
});

describe('a search begun on a shelf', () => {
  const type = (text: string) =>
    act(() => {
      fireEvent.change(screen.getByRole('searchbox'), { target: { value: text } });
    });

  test("starts on that shelf's kind, with the other kinds and All still there", async () => {
    render();
    fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
    await screen.findByRole('button', { name: 'Open Rumours' });
    type('fleetwood');

    expect(await screen.findByRole('tab', { name: 'All' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Albums' }).getAttribute('aria-selected')).toBe('true');
    // Only albums to begin with: no artist or track.
    expect(await screen.findByRole('button', { name: 'Open Rumours' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Open Fleetwood Mac' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Play Dreams' })).toBeNull();

    // All is a tap away.
    fireEvent.click(screen.getByRole('tab', { name: 'All' }));
    expect(await screen.findByRole('button', { name: 'Open Fleetwood Mac' })).toBeTruthy();
  });

  test('keeps the kind when the phrase changes, says so when it finds none of it, and forgets it when cleared', async () => {
    render();
    fireEvent.click(await screen.findByRole('tab', { name: 'Albums' }));
    await screen.findByRole('button', { name: 'Open Rumours' });
    type('fleetwood');
    await screen.findByRole('button', { name: 'Open Rumours' });

    // A phrase with no album in it: still albums, and nothing found, with the kind still chosen.
    type('dreams');
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Albums' }).getAttribute('aria-selected')).toBe(
        'true',
      ),
    );

    expect(await screen.findByText('Nothing found.')).toBeTruthy();

    type('');
    fireEvent.click(await screen.findByRole('tab', { name: 'Recently played' }));
    await screen.findByRole('button', { name: 'Play Dreams' });
    // Recently played holds several kinds: a search from it is the mixed one.
    type('fleetwood');
    expect(await screen.findByRole('button', { name: 'Open Fleetwood Mac' })).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'All' }).getAttribute('aria-selected')).toBe('true');
  });
});

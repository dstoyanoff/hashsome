import type { BrowseItem, BrowseKind, EntityRef } from '@hashsome/core';
import { startTransition, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useReconnects } from './hooks.ts';
import { useClient } from './provider.tsx';

/** How well an item's title answers what was searched for, lowest first: the whole title, then its start, then a word's
 * start, then anywhere in it, then not in the title at all (found by its artist, say). */
function matchRank(title: string, needle: string): number {
  const text = title.toLowerCase();
  const wanted = needle.toLowerCase();
  if (text === wanted) {
    return 0;
  }

  if (text.startsWith(wanted)) {
    return 1;
  }

  if (text.split(/[\s\-_/.,:;()[\]]+/).some((word) => word.startsWith(wanted))) {
    return 2;
  }

  return text.includes(wanted) ? 3 : 4;
}

/** Search results in the order of how well their titles match, an exact match first. Items that match equally keep the
 * order the library gave them, which is its own idea of what matters. */
export function rankSearchResults(items: BrowseItem[], needle: string): BrowseItem[] {
  return items
    .map((item, index) => ({ item, index, rank: matchRank(item.title, needle) }))
    .toSorted((a, b) => a.rank - b.rank || a.index - b.index)
    .map(({ item }) => item);
}

interface Level {
  /** The item that was opened to get here; `undefined` for the top level. */
  path?: string;
  title?: string;
  item?: BrowseItem;

  /** What was searched for, and which kind of result was chosen, when this was opened from the results, so that going back returns to them. */
  from?: { text: string; kind?: BrowseKind | undefined };
}

interface Answer {
  key: string;
  items: BrowseItem[];
  title?: string;
  error?: string;
}

export interface MediaBrowserState {
  /** What is listed now. */
  items: BrowseItem[];

  /** Which list that is: it changes when another one is shown (another tab, an album opened, a search), so a view can tell the same list updating from a new one arriving. */
  listKey: string;

  /** A name for the list: the opened folder's, or what was searched for. Absent on a tab. */
  title: string | undefined;
  loading: boolean;
  error: string | undefined;

  /** The top level's shelves (Playlists, Albums, ...), when it is a set of folders to switch
   * between. They are shown as tabs instead of a list of folders to open, and stay in view while
   * something inside a shelf is open or a search is showing. */
  tabs: BrowseItem[] | undefined;

  /** The open tab; none while a search is showing, since its results belong to no shelf. */
  activeTab: string | undefined;

  /** Goes to a shelf: leaves whatever was open inside one, and any search. */
  selectTab(id: string): void;

  /** The album, playlist or artist that is open: what a "play all" would play, and what the tracks in the list belong to. Absent at the top, on a shelf and in search results. */
  inside: BrowseItem | undefined;

  /** Opened a folder (or searching), so there is somewhere to go back to. */
  canGoBack: boolean;
  searching: boolean;
  open(item: BrowseItem): void;
  back(): void;

  /** What is typed in the search box. */
  query: string;

  /** Lists what matches `text`; an empty string goes back to where the browser was. */
  search(text: string): void;

  /** The kinds a search can be narrowed to (tracks, playlists…), in a fixed order: it is always one of them, never a mix. Absent when not searching. */
  kinds: BrowseKind[] | undefined;

  /** The kind the results are of: the open shelf's, when it holds one kind, else tracks, until another is chosen. Absent when not searching. */
  kind: BrowseKind | undefined;

  /** Searches for another kind of thing. */
  filterBy(kind: BrowseKind): void;
}

/** The order the kinds of a search are offered in: what is played most, first. */
const KIND_ORDER: BrowseKind[] = [
  'track',
  'playlist',
  'album',
  'artist',
  'radio',
  'folder',
  'other',
];

/** What a search is offered for whatever the library, and then for each kind a shelf says it holds (radio, say). */
const ALWAYS_SEARCHABLE: BrowseKind[] = ['track', 'playlist', 'album', 'artist'];

/** A top level that is nothing but folders to look inside is a set of shelves, which read better
 * as tabs than as a list that always looks the same. */
const isShelves = (items: BrowseItem[]) =>
  items.length >= 2 && items.every((item) => item.expandable && !item.playable);

/** How long typing stops before a search is sent. */
const SEARCH_PAUSE = 300;

const messageOf = (error: unknown) => (error instanceof Error ? error.message : String(error));

/** Walks a player's own media library through the runtime. The top level is read once: shelves
 * become tabs (the first is open to begin with), anything else is a plain list. Below it, a stack
 * of opened folders; apart from it, an optional search, and clearing the search returns to where
 * the browser was. */
export function useMediaBrowser(ref: EntityRef): MediaBrowserState {
  const client = useClient();
  // A level that could not be read because the connection was not there is asked for again when it is.
  const reconnects = useReconnects();
  const [trail, setTrail] = useState<Level[]>([{}]);
  const [text, setText] = useState('');
  const [tab, setTab] = useState<string | undefined>();
  // The kind of thing a search is for, once one has been chosen; until then it is the open shelf's, or tracks.
  const [kindChoice, setKindChoice] = useState<BrowseKind | undefined>();
  // Answers are kept with the request they answer, so a stale one is never shown for a new one.
  const [top, setTop] = useState<Answer | null>(null);
  const [answer, setAnswer] = useState<Answer | null>(null);

  useEffect(() => {
    let current = true;
    client.browse(ref, {}).then(
      (level) => current && startTransition(() => setTop({ key: 'top', ...level })),
      (error: unknown) =>
        current &&
        startTransition(() => setTop({ key: 'top', items: [], error: messageOf(error) })),
    );

    return () => {
      current = false;
    };
    // `reconnects` is not read inside: the connection coming back is the reason to ask again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [client, ref, reconnects]);

  const needle = text.trim();
  const searching = needle !== '';
  const here = trail.at(-1) ?? {};
  const atTop = trail.length === 1;
  const tabs = top && !top.error && isShelves(top.items) ? top.items : undefined;
  const selected = tabs ? (tabs.find((item) => item.id === tab) ?? tabs[0])?.id : undefined;

  // A search is for one kind at a time (Music Assistant answers a kind at a time): the one chosen, or the open
  // shelf's when it holds one (the Playlists tab searches playlists), or tracks.
  const holds = tabs?.find((item) => item.id === selected)?.holds;
  const offered = KIND_ORDER.filter(
    (candidate) =>
      ALWAYS_SEARCHABLE.includes(candidate) || tabs?.some((item) => item.holds === candidate),
  );

  const kind: BrowseKind = [kindChoice, holds, 'track' as const].find(
    (candidate): candidate is BrowseKind => candidate !== undefined && offered.includes(candidate),
  )!;

  // What to ask for, if anything: a folder, the open tab, or a kind of thing matching what was typed. A top
  // level that is not shelves is already in hand.
  const path = atTop ? selected : here.path;
  const key = searching ? `search:${needle}:${kind}` : `path:${path ?? ''}`;
  const result = answer?.key === key ? answer : null;
  // The phrase last sent: typing waits for a pause; opening a folder or a tab, or another kind of the same phrase, does not.
  const sent = useRef('');

  useEffect(() => {
    const request = searching
      ? { search: needle, kind }
      : path === undefined
        ? undefined
        : { path };

    if (!request) {
      return;
    }

    const pause = searching && sent.current !== needle ? SEARCH_PAUSE : 0;
    sent.current = searching ? needle : '';

    let current = true;
    const timer = setTimeout(() => {
      client.browse(ref, request).then(
        // A transition: the rows of an answer are a lot to draw, and the page stays alive while they are.
        (level) => current && startTransition(() => setAnswer({ key, ...level })),
        (error: unknown) =>
          current && startTransition(() => setAnswer({ key, items: [], error: messageOf(error) })),
      );
    }, pause);

    return () => {
      current = false;
      clearTimeout(timer);
    };
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [client, ref, key, searching, needle, kind, path, reconnects]);

  // Opening what a search found (an album, a playlist) shows it in place of the results: the search steps
  // aside, and going back from it returns to the results.
  const open = useCallback(
    (item: BrowseItem) => {
      const from = text.trim();
      setTrail((levels) => [
        ...levels,
        {
          path: item.id,
          title: item.title,
          item,
          ...(from !== '' ? { from: { text: from, kind } } : {}),
        },
      ]);

      setText('');
    },
    [text, kind],
  );

  // Clearing the search forgets the kind chosen: the next one starts on the shelf's again.
  const search = useCallback((next: string) => {
    if (next.trim() === '') {
      setKindChoice(undefined);
    }

    setText(next);
  }, []);

  const selectTab = useCallback((id: string) => {
    setTab(id);
    setText('');
    setKindChoice(undefined);
    setTrail([{}]);
  }, []);

  const back = useCallback(() => {
    if (text.trim() !== '') {
      setText('');
      return;
    }

    const left = trail.at(-1);
    setTrail((levels) => (levels.length > 1 ? levels.slice(0, -1) : levels));
    if (trail.length > 1 && left?.from !== undefined) {
      setText(left.from.text);
      setKindChoice(left.from.kind);
    }
  }, [text, trail]);

  // The plain top-level list, when there are no shelves to make tabs of.
  const listing = !searching && atTop && !tabs ? top : result;
  const listed = listing?.items;
  const items = useMemo(
    () => (listed && searching ? rankSearchResults(listed, needle) : (listed ?? [])),
    [listed, searching, needle],
  );

  return {
    items,
    listKey: searching ? `search:${kind}` : `path:${path ?? 'top'}`,
    title: searching
      ? (result?.title ?? `Results for “${needle}”`)
      : atTop
        ? undefined
        : (result?.title ?? here.title),
    loading: listing === null,
    error: listing?.error,
    tabs,
    activeTab: searching ? undefined : selected,
    selectTab,
    inside: searching || atTop ? undefined : here.item,
    canGoBack: searching || !atTop,
    searching,
    open,
    back,
    query: text,
    search,
    kinds: searching ? offered : undefined,
    kind: searching ? kind : undefined,
    filterBy: setKindChoice,
  };
}

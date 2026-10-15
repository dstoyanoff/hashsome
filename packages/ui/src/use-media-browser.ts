import type { BrowseItem, BrowseKind, EntityRef } from '@hashsome/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
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

  /** The kinds of thing the search found (albums, playlists…), when it found any, so there is something to narrow it to (to a lone kind too, which brings far more of it than the mixed list); in a fixed order. Absent when not searching. While another search is on its way these are the last one's, so what is shown does not flicker as someone types. */
  kinds: BrowseKind[] | undefined;

  /** Searching, with no kinds to show yet (the first answer has not come) and nothing from an earlier search to show instead: a placeholder for them. */
  kindsPending: boolean;

  /** The kind the results are narrowed to; absent for all of them. */
  kind: BrowseKind | undefined;

  /** Narrows the search results to one kind, which asks for more of it than the mixed list has, or back to all of them. */
  filterBy(kind: BrowseKind | undefined): void;
}

/** The order the kinds of a search result are offered in: what is played most, first. */
const KIND_ORDER: BrowseKind[] = [
  'track',
  'playlist',
  'album',
  'artist',
  'radio',
  'folder',
  'other',
];

/** The kinds among some items, in the order they are offered. */
const kindsOf = (items: BrowseItem[]) =>
  KIND_ORDER.filter((candidate) => items.some((item) => item.kind === candidate));

/** A top level that is nothing but folders to look inside is a set of shelves, which read better
 * as tabs than as a list that always looks the same. */
const isShelves = (items: BrowseItem[]) =>
  items.length >= 2 && items.every((item) => item.expandable && !item.playable);

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
  // The kind of result chosen to narrow a search to; only counts while that kind is among the results.
  const [kindChoice, setKindChoice] = useState<BrowseKind | undefined>();
  // The search with no kind chosen: it says which kinds there are, and is the list while none is chosen.
  const [overview, setOverview] = useState<Answer | null>(null);
  // The kinds the last answered search found, kept while the next one is on its way.
  const [lastKinds, setLastKinds] = useState<BrowseKind[] | undefined>();
  // Answers are kept with the request they answer, so a stale one is never shown for a new one.
  const [top, setTop] = useState<Answer | null>(null);
  const [answer, setAnswer] = useState<Answer | null>(null);

  useEffect(() => {
    let current = true;
    client.browse(ref, {}).then(
      (level) => current && setTop({ key: 'top', ...level }),
      (error: unknown) => current && setTop({ key: 'top', items: [], error: messageOf(error) }),
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

  // The kinds the search found, once it has answered, and the one chosen if it is among them.
  const overviewKey = `search:${needle}`;
  const found = searching && overview?.key === overviewKey ? overview : null;
  const foundKinds = found ? kindsOf(found.items) : undefined;

  if (!searching && lastKinds !== undefined) {
    setLastKinds(undefined);
  }

  // What to offer: this search's kinds once they are in, the last search's until then, and the one chosen even if
  // this search found none of it (a search begun on a shelf starts on that shelf's kind: "nothing found" is an answer).
  const offered = searching ? (foundKinds ?? lastKinds) : undefined;
  const kinds =
    offered && kindChoice !== undefined && !offered.includes(kindChoice)
      ? KIND_ORDER.filter((candidate) => candidate === kindChoice || offered.includes(candidate))
      : offered;

  const kind = kindChoice !== undefined && kinds?.includes(kindChoice) ? kindChoice : undefined;

  // What to ask for, if anything: a folder, the open tab, or one kind of a search's results. A top level
  // that is not shelves is already in hand, and so is the search with no kind chosen.
  const path = atTop ? selected : here.path;
  const key = searching ? `search:${needle}:${kind ?? ''}` : `path:${path ?? ''}`;
  const result = answer?.key === key ? answer : null;

  // The search with no kind chosen, always: its kinds are the choices, whichever one is showing.
  useEffect(() => {
    if (!searching) {
      return;
    }

    let current = true;
    const timer = setTimeout(() => {
      client.browse(ref, { search: needle }).then(
        (level) => {
          if (current) {
            setOverview({ key: `search:${needle}`, ...level });
            setLastKinds(kindsOf(level.items));
          }
        },
        (error: unknown) =>
          current && setOverview({ key: `search:${needle}`, items: [], error: messageOf(error) }),
      );
    }, 300);

    return () => {
      current = false;
      clearTimeout(timer);
    };
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [client, ref, searching, needle, reconnects]);

  useEffect(() => {
    const request =
      searching && kind
        ? { search: needle, kind }
        : searching
          ? undefined
          : path === undefined
            ? undefined
            : { path };

    if (!request) {
      return;
    }

    let current = true;
    const timer = setTimeout(
      () => {
        client.browse(ref, request).then(
          (level) => current && setAnswer({ key, ...level }),
          (error: unknown) => current && setAnswer({ key, items: [], error: messageOf(error) }),
        );
      },
      // Typing in the search box waits for a pause; opening a folder or a tab or choosing a kind does not.
      0,
    );

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

  // Typing into the search begins it on the open shelf's kind, when the shelf holds one kind (the Playlists tab
  // searches playlists, with "All" a tap away); clearing it forgets the choice.
  const holds = tabs?.find((item) => item.id === selected)?.holds;
  const search = useCallback(
    (next: string) => {
      if (next.trim() === '') {
        setKindChoice(undefined);
      } else if (text.trim() === '' && atTop) {
        setKindChoice(holds);
      }

      setText(next);
    },
    [text, atTop, holds],
  );

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
  const listing = searching && !kind ? found : !searching && atTop && !tabs ? top : result;
  const listed = listing?.items;
  const items = useMemo(
    () => (listed && searching ? rankSearchResults(listed, needle) : (listed ?? [])),
    [listed, searching, needle],
  );

  return {
    items,
    listKey: searching ? `search:${kind ?? ''}` : `path:${path ?? 'top'}`,
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
    kinds: kinds && kinds.length >= 1 ? kinds : undefined,
    kindsPending: searching && foundKinds === undefined && !(lastKinds && lastKinds.length >= 1),
    kind,
    filterBy: setKindChoice,
  };
}

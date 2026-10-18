import {
  ArrowLeft,
  ChevronRight,
  Disc3,
  Folder,
  ListMusic,
  ListPlus,
  ListStart,
  MicVocal,
  Music,
  Pause,
  Play,
  Radio,
  Search,
  Shuffle,
  X,
} from 'lucide-react';
import type { BrowseItem, BrowseKind, EntityRef, MediaPlayerCommands } from '@hashsome/core';
import { AnimatePresence, motion } from 'motion/react';
import {
  memo,
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type MouseEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';
import { Icon } from '../icon.tsx';
import { useEntityHandle } from '../hooks.ts';
import { useDetail } from '../layout/detail-provider.tsx';
import { Cover } from '../layout/cover.tsx';
import { CHIP_HEIGHT, ChipRow } from '../layout/drawer-controls.tsx';
import { FadeScroll } from '../layout/fade-scroll.tsx';
import { MarqueeText } from '../layout/marquee-text.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { Spinner } from '../layout/spinner.tsx';
import { Reveal } from '../layout/reveal.tsx';
import { useStill } from '../layout/use-still.ts';
import { Skeleton } from '../layout/skeleton.tsx';
import { IconButton } from '../layout/tile.tsx';
import { useMediaBrowser } from '../use-media-browser.ts';
import { useRows } from '../use-rows.ts';
import { trackMedia, useMediaPending, type MediaPending } from './media-pending.ts';
import styles from './media-browser.module.css';
import { formatLength } from './media-progress.ts';

export interface MediaBrowserProps {
  /** The media player whose own library to browse, as a ref like `ma:living_room`. The backend that owns the player is the media source, so a Music Assistant player lists Music Assistant's library and a Home Assistant player Home Assistant's. */
  entity: EntityRef;

  /** Called after something is started, e.g. to close a drawer the browser sits in. */
  onPlay?: (item: BrowseItem) => void;

  /** How items are laid out. `list` is rows, compact enough for a narrow drawer. `theater` is a single row of large cards that scrolls sideways, for a wide space. `auto` is `theater` inside an expanded drawer and `list` anywhere else. Default `list`. */
  layout?: 'list' | 'theater' | 'auto';

  /** How the search is offered, for a library that can be searched. `bar` is a field above the list. `icon` is a search icon at the right end of the row of tabs (or of the heading), after a thin line, which becomes the field when pressed, in that same row, so search takes no room of its own; its close button puts the tabs back. Default `bar`. */
  search?: 'bar' | 'icon';
}

/** What is in an album, a playlist or an artist that has been opened, in a few words: `42 songs · 2 h 51 min`, `12 albums`.
 * The length is only said when every track has one, so it is never a total of some of them. */
function summaryOf(items: readonly BrowseItem[]): string {
  const NOUNS: Partial<Record<BrowseKind, [string, string]>> = {
    track: ['song', 'songs'],
    album: ['album', 'albums'],
    playlist: ['playlist', 'playlists'],
    artist: ['artist', 'artists'],
    radio: ['station', 'stations'],
  };

  const kinds = new Set(items.map((item) => item.kind));
  const [one, many] = (kinds.size === 1 ? NOUNS[items[0]!.kind] : undefined) ?? ['item', 'items'];
  const count = `${items.length} ${items.length === 1 ? one : many}`;
  const lengths = items.map((item) => item.duration);
  return kinds.size === 1 && items[0]!.kind === 'track' && lengths.every((x) => x !== undefined)
    ? `${count} · ${formatLength(lengths.reduce((sum, x) => sum + x, 0))}`
    : count;
}

/** The height of the search field, and of the row of tabs it replaces. */
const SEARCH_HEIGHT = 36;

/** The name of each kind of thing a search finds, on the chip that narrows the results to it. */
const KIND_LABELS: Record<BrowseKind, string> = {
  album: 'Albums',
  artist: 'Artists',
  track: 'Tracks',
  playlist: 'Playlists',
  radio: 'Radio',
  folder: 'Folders',
  other: 'Other',
};

/** How far, in px, a swipe across the list goes before it means "back". */
const SWIPE_BACK = 72;

const KIND_ICONS: Record<BrowseKind, ReactNode> = {
  folder: <Folder />,
  artist: <MicVocal />,
  album: <Disc3 />,
  playlist: <ListMusic />,
  track: <Music />,
  radio: <Radio />,
  other: <Music />,
};

/** The library of a media player, in the app's own style: shelves, albums, playlists and the rest
 * as rows with artwork. Tap a folder to open it, a track or station to play it, or the play button
 * on an album, playlist or artist to play all of it. A search box appears when the library can be
 * searched. */
export function MediaBrowser({
  entity,
  onPlay,
  layout = 'list',
  search: searchMode = 'bar',
}: MediaBrowserProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const { detail } = useDetail();
  const theater = layout === 'theater' || (layout === 'auto' && detail?.expanded === true);
  const browser = useMediaBrowser(entity);
  const {
    shown: rows,
    attach: attachRows,
    onScroll: scrollRows,
  } = useRows(browser.items, browser.listKey);

  const still = useStill();
  // `icon`: the field is open (or has something in it); otherwise only the icon is there.
  const [searchOpen, setSearchOpen] = useState(false);
  const player = handle.entity;
  // A player with a queue can be told to add to it, play next and shuffle; one without can only play.
  const queueable = player?.capabilities.queue === true;

  /** Asking the player to play something can take it many seconds; until it answers, the item and the
   * player say so. */
  const waiting = useMediaPending(entity);
  const ask = (
    kind: MediaPending['kind'],
    item: BrowseItem,
    args: MediaPlayerCommands['playMedia'],
  ) =>
    trackMedia(
      entity,
      { kind, item: item.id, label: item.title },
      handle.command('playMedia', args),
    );

  /** The playlist, album, artist or station the queue was filled from, and the track playing, are marked in the list
   * and carry the player's play/pause in place of their own actions (it would only start itself again). The track is
   * found by its name and artist: the player does not say which item it is. */
  const media = player?.media;
  const activeOf = (item: BrowseItem) =>
    (player?.source !== undefined && item.id === player.source.id) ||
    (item.kind === 'track' &&
      media?.title !== undefined &&
      item.title === media.title &&
      (item.subtitle === undefined ||
        media.artist === undefined ||
        item.subtitle === media.artist));

  const nowPlaying = player?.playback === 'playing';
  const togglePlay = () => void handle.command('togglePlay');

  const askedOf = (item: BrowseItem) =>
    waiting.filter((entry) => entry.item === item.id).map((entry) => entry.kind);

  /** What the actions do. Playing replaces the queue, as in Spotify: a track in an album or playlist
   * queues all of it and starts there, so Next goes to the next track of the album. */
  const play = (item: BrowseItem) => {
    const container = browser.inside;
    const within =
      item.kind === 'track' && container && ['album', 'playlist'].includes(container.kind)
        ? container
        : undefined;

    ask('playItem', item, {
      item: item.id,
      mode: 'replace',
      ...(within ? { context: within.id } : {}),
    });

    onPlay?.(item);
  };

  const playNext = (item: BrowseItem) => ask('queueNext', item, { item: item.id, mode: 'next' });

  const addToQueue = (item: BrowseItem) => ask('queueAdd', item, { item: item.id, mode: 'add' });

  const shuffleAll = (item: BrowseItem) => {
    ask('playItem', item, { item: item.id, mode: 'replace', shuffle: true });
    onPlay?.(item);
  };

  /** Something is open inside the selected shelf (not a search): the tab shows the way back. */
  const inside = browser.canGoBack && !browser.searching;

  /** Opens an album, playlist or artist, from the shelf or from search results. A search field opened from its icon
   * is put away: it would otherwise sit blank over the row of tabs, which is where the way back is. Going back
   * returns to the results, with what was searched for in the field again. */
  const openItem = (item: BrowseItem) => {
    browser.open(item);
    setSearchOpen(false);
  };

  /** What a row does, as of the latest render: a row is not drawn again for a change that is not its own (see
   * `BrowseRow`), so what it holds must never be what was true when it was last drawn. */
  const latest = useRef({ openItem, play, playNext, addToQueue, togglePlay });
  useLayoutEffect(() => {
    latest.current = { openItem, play, playNext, addToQueue, togglePlay };
  });

  /** Swiping right across the list goes back out of what is open, as in a native app: a clearly sideways move, so
   * scrolling up and down is left alone. The tap that ends a swipe is not passed on to the row under the finger. */
  const swipe = useRef<{ x: number; y: number; swiped: boolean } | null>(null);
  const swipeProps = {
    onPointerDown: (event: PointerEvent<HTMLElement>) => {
      const start = { x: event.clientX, y: event.clientY, swiped: false };
      swipe.current = start;
      if (!inside) {
        return;
      }

      // The finger may lift well outside the list, so the end of the gesture is heard on the window.
      const end = (up: globalThis.PointerEvent) => {
        window.removeEventListener('pointerup', end);
        window.removeEventListener('pointercancel', end);
        const dx = up.clientX - start.x;
        const dy = up.clientY - start.y;
        if (up.type === 'pointerup' && dx > SWIPE_BACK && dx > 2 * Math.abs(dy)) {
          start.swiped = true;
          browser.back();
        }
      };

      window.addEventListener('pointerup', end);
      window.addEventListener('pointercancel', end);
    },
    onClickCapture: (event: MouseEvent<HTMLElement>) => {
      if (swipe.current?.swiped) {
        event.stopPropagation();
        event.preventDefault();
      }

      swipe.current = null;
    },
  };

  /** What can be done with the whole album, playlist or artist that is open: play it, shuffle it,
   * add it to the queue. Playing it replaces the queue. They sit at the end of the row of tabs (or
   * of the heading), never in a row of their own, so opening an album moves nothing; where there is
   * no room for words they are just their icons. */
  const opened = browser.inside;
  const actionsHere =
    opened?.playable === true ? (
      <div className={styles.actions}>
        <PillButton
          icon={<Play />}
          label="Play"
          compact={!theater}
          onClick={() => play(opened)}
          primary
        />
        {queueable ? (
          <>
            <PillButton
              icon={<Shuffle />}
              label="Shuffle"
              compact={!theater}
              onClick={() => shuffleAll(opened)}
            />
            <PillButton
              icon={<ListPlus />}
              label="Add to queue"
              compact={!theater}
              onClick={() => addToQueue(opened)}
            />
          </>
        ) : null}
      </div>
    ) : null;

  const canSearch = player?.capabilities.search === true;
  const onDemand = searchMode === 'icon' && canSearch;
  const fieldShown = onDemand && (searchOpen || browser.query !== '');
  const closeSearch = () => {
    browser.search('');
    setSearchOpen(false);
  };

  /** `icon`: a thin line and the search icon, fixed at the end of the row. It is left out inside an album or playlist:
   * a search there would open empty, over the way back, and the results of one are better found from the top. */
  const searchTrigger = onDemand ? (
    <div className={styles.trigger}>
      <span className={styles.rule} />
      <PlainButton
        className={styles.searchButton}
        aria-label="Search"
        title="Search"
        onClick={() => setSearchOpen(true)}
      >
        <Icon size={18}>
          <Search />
        </Icon>
      </PlainButton>
    </div>
  ) : null;

  const header = (
    <div className={styles.header}>
      {browser.canGoBack ? (
        <IconButton icon={<ArrowLeft />} label="Back" glyph={16} onClick={browser.back} />
      ) : null}
      <h2 className={styles.heading}>{browser.title ?? 'Library'}</h2>
      {actionsHere}
      {searchTrigger}
    </div>
  );

  const search = canSearch ? (
    <div className={styles.search}>
      <Icon size={16}>
        <Search />
      </Icon>
      <input
        className={styles.input}
        type="search"
        aria-label="Search the library"
        placeholder="Search"
        value={browser.query}
        onChange={(event) => browser.search(event.target.value)}
        // Opened by pressing the icon, so it is for typing straight away. Coming back to the results with the phrase
        // in it is not that: the keyboard would come up over what was being looked for.
        {...(onDemand
          ? {
              autoFocus: browser.query === '',
              onKeyDown: (event: { key: string }) => {
                if (event.key === 'Escape') {
                  closeSearch();
                }
              },
            }
          : {})}
      />
      {onDemand ? (
        <PlainButton
          className={styles.clear}
          aria-label="Close search"
          title="Close search"
          onClick={closeSearch}
        >
          <Icon size={16}>
            <X />
          </Icon>
        </PlainButton>
      ) : browser.query !== '' ? (
        <PlainButton
          className={styles.clear}
          aria-label="Clear search"
          title="Clear search"
          onClick={() => browser.search('')}
        >
          <Icon size={16}>
            <X />
          </Icon>
        </PlainButton>
      ) : null}
    </div>
  ) : null;

  /** The categories (and, with `search='icon'`, the search icon at the end). While searching, the same row is the
   * kinds to search for: the shelves mean nothing to a search's results, which belong to none. */
  const tabsRowFor = (trailing: boolean) =>
    browser.tabs ? (
      // With the search icon in it, as tall as the field that replaces it, so the list below stays put.
      <div className={styles.tabs} data-on-demand={onDemand && trailing}>
        <div className={styles.tabsMain}>
          {browser.searching && browser.kinds ? (
            <ChipRow
              tabs
              options={browser.kinds.map((kind) => ({ value: kind, label: KIND_LABELS[kind] }))}
              value={browser.kind ?? ''}
              onChange={(id) => browser.filterBy(id as BrowseKind)}
            />
          ) : (
            <ChipRow
              tabs
              options={browser.tabs.map((item) =>
                // Inside a shelf the selected tab becomes the way back, so nothing is added to the
                // layout and nothing below it moves.
                item.id === browser.activeTab && inside
                  ? {
                      value: item.id,
                      label: browser.title ?? item.title,
                      icon: <ArrowLeft />,
                      ariaLabel: 'Back',
                    }
                  : { value: item.id, label: item.title },
              )}
              value={browser.activeTab}
              onChange={(id) =>
                id === browser.activeTab && inside ? browser.back() : browser.selectTab(id)
              }
            />
          )}
        </div>
        {trailing ? actionsHere : null}
        {trailing && !inside ? searchTrigger : null}
      </div>
    ) : null;

  const tabsRow = tabsRowFor(true);

  const fieldOrRow = fieldShown ? (
    // The field has taken the row's place, so what the results are of (the kinds to narrow to) goes under it.
    <div className={styles.field}>
      {search}
      {browser.searching ? tabsRowFor(false) : null}
    </div>
  ) : browser.tabs === undefined ? (
    header
  ) : (
    tabsRow
  );

  return (
    <div
      className={styles.browser}
      style={
        {
          '--search-height': `${SEARCH_HEIGHT}px`,
          '--card-size': CARD_SIZE,
        } as CSSProperties
      }
    >
      {/* With shelves, search and tabs are a fixed frame and only what is below them changes; a
          list has no frame, so its heading row, always there, comes first. */}
      {onDemand ? (
        // The field comes in from the icon's side as the row fades out under it; the row is as tall as
        // the field, so neither moves what is below. Nothing to wait for: they cross.
        still ? (
          fieldOrRow
        ) : (
          <AnimatePresence initial={false} mode="popLayout">
            <motion.div
              key={fieldShown ? 'field' : 'row'}
              initial={{ opacity: 0, x: fieldShown ? 28 : 0 }}
              animate={{ opacity: 1, x: 0 }}
              exit={{ opacity: 0, x: fieldShown ? 0 : 28 }}
              transition={{ duration: 0.18, ease: 'easeOut' }}
            >
              {fieldOrRow}
            </motion.div>
          </AnimatePresence>
        )
      ) : (
        <>
          {browser.tabs === undefined ? header : null}
          {search}
          {tabsRow}
        </>
      )}
      {browser.error ? (
        <Message theater={theater}>
          <p className={styles.error} role="alert">
            {browser.error}
          </p>
        </Message>
      ) : browser.loading ? (
        <BrowseSkeleton theater={theater} />
      ) : browser.items.length === 0 ? (
        <Message theater={theater}>
          <p className={styles.message}>{browser.searching ? 'Nothing found.' : 'Nothing here.'}</p>
        </Message>
      ) : theater ? (
        <FadeScroll as="ul" className={styles.theater}>
          {browser.items.map((item, index) => (
            // Keyed by the list too, so another list arriving is new items fading in, not the same
            // ones left as they were; typing in the search does not stagger them.
            <li key={`${browser.listKey}:${item.id}`} className={styles.card}>
              <Reveal index={browser.searching ? 0 : index}>
                <BrowseCard
                  item={item}
                  active={activeOf(item)}
                  playing={nowPlaying}
                  onToggle={togglePlay}
                  asked={askedOf(item)}
                  queueable={queueable}
                  onOpen={() => openItem(item)}
                  onPlay={() => play(item)}
                  onAdd={() => addToQueue(item)}
                />
              </Reveal>
            </li>
          ))}
        </FadeScroll>
      ) : (
        <ul className={styles.rows} {...swipeProps} ref={attachRows} onScroll={scrollRows}>
          {rows.map((item, index) => (
            <li key={`${browser.listKey}:${item.id}`}>
              <Reveal index={browser.searching ? 0 : index}>
                <BrowseRow
                  item={item}
                  active={activeOf(item)}
                  playing={nowPlaying}
                  onToggle={() => latest.current.togglePlay()}
                  asked={askedOf(item)}
                  queueable={queueable}
                  onOpen={() => latest.current.openItem(item)}
                  onPlay={() => latest.current.play(item)}
                  onNext={() => latest.current.playNext(item)}
                  onAdd={() => latest.current.addToQueue(item)}
                />
              </Reveal>
            </li>
          ))}
          {/* What is in what is open, at the end of its list: a line above the rows would push them down on opening, and
              opening something moves nothing. */}
          {inside ? (
            <li>
              <p className={styles.summary}>{summaryOf(browser.items)}</p>
            </li>
          ) : null}
        </ul>
      )}
    </div>
  );
}

/** What shows where the list will be while it loads: placeholders shaped like what is coming (cards in
 * the theater layout, rows in the list), in the room the real thing takes, so nothing moves. */
function BrowseSkeleton({ theater }: { theater: boolean }) {
  return theater ? (
    <ul
      className={styles.loading}
      role="status"
      aria-label="Loading"
      aria-busy="true"
      style={{ '--card-size': CARD_SIZE } as CSSProperties}
    >
      {Array.from({ length: 12 }, (_, index) => (
        <li key={index} className={styles.loadingCard}>
          <Skeleton width={CARD_SIZE} height={CARD_SIZE} radius="card" />
          <div className={styles.loadingText}>
            <Skeleton width="70%" height={14} />
            <Skeleton width="45%" height={12} />
          </div>
        </li>
      ))}
    </ul>
  ) : (
    <ul className={styles.loadingRows} role="status" aria-label="Loading" aria-busy="true">
      {Array.from({ length: 6 }, (_, index) => (
        <li key={index} className={styles.loadingRow}>
          <Skeleton width={44} height={44} />
          <div className={styles.loadingRowText}>
            <Skeleton width="60%" height={14} />
            <Skeleton width="35%" height={12} />
          </div>
        </li>
      ))}
    </ul>
  );
}

/** A line of text where the list would be (an error, nothing found), in the room the list takes in
 * the theater layout, so showing it does not change the library's height. */
function Message({ theater, children }: { theater: boolean; children: ReactNode }) {
  return theater ? <div className={styles.messageStage}>{children}</div> : <>{children}</>;
}

/** A labelled round button, for the actions of the album or playlist that is open. */
function PillButton({
  icon,
  label,
  onClick,
  primary,
  compact,
}: {
  icon: ReactNode;
  label: string;
  onClick: () => void;
  primary?: boolean;

  /** Only the icon, for a narrow space. */
  compact?: boolean;
}) {
  // Built like a tab (a button with the same padding), so the row they share does not change height.
  return (
    <button
      type="button"
      className={styles.pill}
      data-primary={primary === true}
      style={{ '--chip-height': `${CHIP_HEIGHT}px` } as CSSProperties}
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      <Icon size={14}>{icon}</Icon>
      {compact ? null : label}
    </button>
  );
}

/** A row is drawn again when what it shows changes: the item, whether it is the one playing (and, if so, whether it
 * plays), what has been asked of it. The handlers are not compared, they only ever call the latest of what they stand for. */
function sameRow(a: RowProps, b: RowProps) {
  return (
    a.item === b.item &&
    a.active === b.active &&
    (!a.active || a.playing === b.playing) &&
    a.queueable === b.queueable &&
    a.asked.join() === b.asked.join()
  );
}

type RowProps = Parameters<typeof BrowseRowView>[0];

const BrowseRow = memo(BrowseRowView, sameRow);

function BrowseRowView({
  item,
  active,
  playing,
  onToggle,
  asked,
  queueable,
  onOpen,
  onPlay,
  onNext,
  onAdd,
}: {
  item: BrowseItem;

  /** It is what the player is playing, or playing from. */
  active: boolean;

  /** The player is playing, so the active item's button pauses it. */
  playing: boolean;
  onToggle: () => void;

  /** What has been asked of the player for this item and not answered yet. */
  asked: string[];
  queueable: boolean;
  onOpen: () => void;
  onPlay: () => void;
  onNext: () => void;
  onAdd: () => void;
}) {
  // A row opens what can be opened and otherwise plays; an openable thing that can also be played
  // (an album) gets its own play button.
  // The track playing carries the player's play/pause, not another start; a playlist or album opens as any does.
  const main = item.expandable ? onOpen : item.playable ? (active ? onToggle : onPlay) : undefined;
  // How many buttons the row has when it is not the active one, less the one that takes their place.
  const spare = Math.max(
    0,
    (queueable && item.playable ? 2 : 0) + (item.expandable && item.playable ? 1 : 0) - 1,
  );

  return (
    <div className={styles.row}>
      <PlainButton
        className={styles.main}
        disabled={!main}
        aria-label={item.expandable ? `Open ${item.title}` : `Play ${item.title}`}
        onClick={main}
      >
        <span className={styles.art}>
          {item.artworkUrl ? (
            <Cover src={item.artworkUrl} size="small" />
          ) : (
            <Icon size={20}>{KIND_ICONS[item.kind]}</Icon>
          )}
          {asked.includes('playItem') ? <Waiting size={20} /> : null}
        </span>
        <span className={styles.names}>
          <span className={styles.title} data-active={active}>
            <MarqueeText>{item.title}</MarqueeText>
          </span>
          {item.subtitle ? (
            <span className={styles.subtitle}>
              <MarqueeText>{item.subtitle}</MarqueeText>
            </span>
          ) : null}
        </span>
        {item.expandable ? <Opens /> : null}
      </PlainButton>
      {active ? (
        <>
          {/* The room the other buttons take is kept, empty, so the text and the chevron line up with
              the rows around it. */}
          {Array.from({ length: spare }, (_, slot) => (
            <span key={slot} aria-hidden="true" className={styles.hidden}>
              <IconButton icon={<Play />} label="" glyph={16} disabled />
            </span>
          ))}
          <IconButton
            icon={playing ? <Pause /> : <Play />}
            label={playing ? `Pause ${item.title}` : `Resume ${item.title}`}
            glyph={16}
            primary
            onClick={onToggle}
          />
        </>
      ) : null}
      {!active && queueable && item.playable ? (
        <>
          <IconButton
            icon={<ListStart />}
            label={`Play ${item.title} next`}
            glyph={16}
            disabled={asked.includes('queueNext')}
            feedback={asked.includes('queueNext') ? 'pending' : undefined}
            onClick={onNext}
          />
          <IconButton
            icon={<ListPlus />}
            label={`Add ${item.title} to the queue`}
            glyph={16}
            disabled={asked.includes('queueAdd')}
            feedback={asked.includes('queueAdd') ? 'pending' : undefined}
            onClick={onAdd}
          />
        </>
      ) : null}
      {!active && item.expandable && item.playable ? (
        <IconButton
          icon={<Play />}
          label={`Play ${item.title}`}
          glyph={16}
          disabled={asked.includes('playItem')}
          feedback={asked.includes('playItem') ? 'pending' : undefined}
          onClick={onPlay}
        />
      ) : null}
    </div>
  );
}

/** The room the theater row takes, whatever it holds: it asks for full-size cards and gives way when
 * there is less, and is a size container so the cards can shrink to fit. What stands in for the row
 * while it loads, or when it is empty, takes the same room, so the library does not change height. */
/** The width and height of a theater card's artwork when there is room for it. */
const CARD = 200;

/** The smallest a theater card's artwork gets: when there is less room than that, the page scrolls instead. */
const MIN_CARD = 120;

/** What a theater card needs under its artwork for the title and subtitle. */
const LABEL = 56;

/** The artwork's side: `CARD`, or less when the row it sits in is shorter than the card and its labels (`cqh` is a percent of that row's height). */
const CARD_SIZE = `min(${CARD}px, max(${MIN_CARD}px, calc(100cqh - ${LABEL}px)))`;

/** An item as a large card for the theater layout: big artwork (round for an artist), then the
 * title and subtitle. It opens or plays like a row does, and an album or playlist gets its play
 * button over the artwork's corner. */
function BrowseCard({
  item,
  active,
  playing,
  onToggle,
  asked,
  queueable,
  onOpen,
  onPlay,
  onAdd,
}: {
  item: BrowseItem;

  /** It is what the player is playing, or playing from. */
  active: boolean;

  /** The player is playing, so the active item's button pauses it. */
  playing: boolean;
  onToggle: () => void;

  /** What has been asked of the player for this item and not answered yet. */
  asked: string[];
  queueable: boolean;
  onOpen: () => void;
  onPlay: () => void;
  onAdd: () => void;
}) {
  const main = item.expandable ? onOpen : item.playable ? (active ? onToggle : onPlay) : undefined;
  return (
    <div className={styles.big}>
      <PlainButton
        className={styles.bigMain}
        disabled={!main}
        aria-label={item.expandable ? `Open ${item.title}` : `Play ${item.title}`}
        onClick={main}
      >
        <span className={styles.bigArt} data-kind={item.kind}>
          {item.artworkUrl ? (
            <Cover src={item.artworkUrl} size="medium" />
          ) : (
            <Icon size={56}>{KIND_ICONS[item.kind]}</Icon>
          )}
          {asked.includes('playItem') ? <Waiting size={32} /> : null}
        </span>
        <span className={styles.bigNames}>
          <span className={styles.bigTitle}>
            <span className={styles.title} data-active={active}>
              <MarqueeText>{item.title}</MarqueeText>
            </span>
            {item.expandable ? <Opens /> : null}
          </span>
          {item.subtitle ? (
            <span className={styles.subtitle}>
              <MarqueeText>{item.subtitle}</MarqueeText>
            </span>
          ) : null}
        </span>
      </PlainButton>
      {active ? (
        <div className={styles.over}>
          <IconButton
            icon={playing ? <Pause /> : <Play />}
            label={playing ? `Pause ${item.title}` : `Resume ${item.title}`}
            glyph={16}
            primary
            onClick={onToggle}
          />
        </div>
      ) : item.playable && (queueable || item.expandable) ? (
        <div className={styles.over}>
          {queueable ? (
            <IconButton
              icon={<ListPlus />}
              label={`Add ${item.title} to the queue`}
              glyph={16}
              disabled={asked.includes('queueAdd')}
              feedback={asked.includes('queueAdd') ? 'pending' : undefined}
              onClick={onAdd}
            />
          ) : null}
          {item.expandable ? (
            <IconButton
              icon={<Play />}
              label={`Play ${item.title}`}
              glyph={16}
              disabled={asked.includes('playItem')}
              feedback={asked.includes('playItem') ? 'pending' : undefined}
              onClick={onPlay}
            />
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

/** Over an item's artwork while the player has not answered a request to play it. */
function Waiting({ size }: { size: number }) {
  return (
    <span className={styles.waiting}>
      <span className={styles.spin}>
        <Spinner size={size} />
      </span>
    </span>
  );
}

/** Marks something that opens (an album, a playlist, an artist, a folder) and is not played by a tap, as against a track. */
function Opens() {
  return (
    <span className={styles.opens} aria-hidden="true">
      <Icon size={16}>
        <ChevronRight />
      </Icon>
    </span>
  );
}

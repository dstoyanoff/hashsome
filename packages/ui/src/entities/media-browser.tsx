/** @jsxImportSource @emotion/react */
import type { BrowseItem, BrowseKind, EntityRef, MediaPlayerCommands } from '@hashsome/core';
import { Box, Flex, Typography } from 'e-prim';
import { AnimatePresence, motion } from 'motion/react';
import { useRef, useState, type MouseEvent, type PointerEvent, type ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
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
import { Skeleton } from '../layout/skeleton.tsx';
import { IconButton } from '../layout/tile.tsx';
import { useMediaBrowser } from '../use-media-browser.ts';
import { trackMedia, useMediaPending, type MediaPending } from './media-pending.ts';

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

const KIND_ICONS: Record<BrowseKind, IconName> = {
  folder: 'lu:folder',
  artist: 'lu:mic-vocal',
  album: 'lu:disc-3',
  playlist: 'lu:list-music',
  track: 'lu:music',
  radio: 'lu:radio',
  other: 'lu:music',
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
      <Flex align="center" gap={2} css={{ flexShrink: 0 }}>
        <PillButton
          icon="lu:play"
          label="Play"
          compact={!theater}
          onClick={() => play(opened)}
          primary
        />
        {queueable ? (
          <>
            <PillButton
              icon="lu:shuffle"
              label="Shuffle"
              compact={!theater}
              onClick={() => shuffleAll(opened)}
            />
            <PillButton
              icon="lu:list-plus"
              label="Add to queue"
              compact={!theater}
              onClick={() => addToQueue(opened)}
            />
          </>
        ) : null}
      </Flex>
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
    <Flex align="center" gap={2} css={{ flexShrink: 0 }}>
      <Box width={1} height={20} background="border" />
      {/* No circle behind it: it is a mark at the end of the row, the size of a touch target. */}
      <PlainButton
        aria-label="Search"
        title="Search"
        onClick={() => setSearchOpen(true)}
        center
        color="textMuted"
        width={SEARCH_HEIGHT}
        height={SEARCH_HEIGHT}
        css={{ flex: 'none' }}
      >
        <Icon name="lu:search" size={18} />
      </PlainButton>
    </Flex>
  ) : null;

  const header = (
    <Flex align="center" gap={2} css={{ flexShrink: 0 }}>
      {browser.canGoBack ? (
        <IconButton icon="lu:arrow-left" label="Back" glyph={16} onClick={browser.back} />
      ) : null}
      <Typography as="h2" variant="heading" grow={1} m={0}>
        {browser.title ?? 'Library'}
      </Typography>
      {actionsHere}
      {searchTrigger}
    </Flex>
  );

  const search = canSearch ? (
    <Flex
      align="center"
      gap={2}
      background="surfaceRaised"
      radius="full"
      px={3}
      height={SEARCH_HEIGHT}
      color="textMuted"
      // Fixed height: when the list takes all the room, the search keeps its size and the list scrolls.
      css={{ flexShrink: 0 }}
    >
      <Icon name="lu:search" size={16} />
      <Box
        as="input"
        type="search"
        aria-label="Search the library"
        placeholder="Search"
        typography="body"
        color="text"
        grow={1}
        minWidth={0}
        value={browser.query}
        onChange={(event: { target: { value: string } }) => browser.search(event.target.value)}
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
        css={{
          background: 'none',
          outline: 'none',
          // A search field gets a clear button of the browser's own in some browsers, and this one has its own
          // (and a button to close it), so the browser's is not drawn.
          '&::-webkit-search-cancel-button, &::-webkit-search-decoration': {
            WebkitAppearance: 'none',
            appearance: 'none',
            display: 'none',
          },
        }}
      />
      {onDemand ? (
        <PlainButton
          aria-label="Close search"
          title="Close search"
          onClick={closeSearch}
          center
          css={{ flex: 'none' }}
        >
          <Icon name="lu:x" size={16} />
        </PlainButton>
      ) : browser.query !== '' ? (
        <PlainButton
          aria-label="Clear search"
          title="Clear search"
          onClick={() => browser.search('')}
          center
          css={{ flex: 'none' }}
        >
          <Icon name="lu:x" size={16} />
        </PlainButton>
      ) : null}
    </Flex>
  ) : null;

  /** The categories (and, with `search='icon'`, the search icon at the end). While searching, the same row is the
   * kinds to search for: the shelves mean nothing to a search's results, which belong to none. */
  const tabsRowFor = (trailing: boolean) =>
    browser.tabs ? (
      // With the search icon in it, as tall as the field that replaces it, so the list below stays put.
      <Flex align="center" gap={3} {...(onDemand && trailing ? { minHeight: SEARCH_HEIGHT } : {})}>
        <Flex direction="column" grow={1} minWidth={0}>
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
                      icon: 'lu:arrow-left' as const,
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
        </Flex>
        {trailing ? actionsHere : null}
        {trailing && !inside ? searchTrigger : null}
      </Flex>
    ) : null;

  const tabsRow = tabsRowFor(true);

  return (
    <Flex direction="column" gap={3} minHeight={0} position="relative">
      {/* With shelves, search and tabs are a fixed frame and only what is below them changes; a
          list has no frame, so its heading row, always there, comes first. */}
      {onDemand ? (
        // The field comes in from the icon's side as the row fades out under it; the row is as tall as
        // the field, so neither moves what is below. Nothing to wait for: they cross.
        <AnimatePresence initial={false} mode="popLayout">
          <motion.div
            key={fieldShown ? 'field' : 'row'}
            initial={{ opacity: 0, x: fieldShown ? 28 : 0 }}
            animate={{ opacity: 1, x: 0 }}
            exit={{ opacity: 0, x: fieldShown ? 0 : 28 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
          >
            {fieldShown ? (
              // The field has taken the row's place, so what the results are of (the kinds to narrow to) goes under it.
              <Flex direction="column" gap={3}>
                {search}
                {browser.searching ? tabsRowFor(false) : null}
              </Flex>
            ) : browser.tabs === undefined ? (
              header
            ) : (
              tabsRow
            )}
          </motion.div>
        </AnimatePresence>
      ) : (
        <>
          {browser.tabs === undefined ? header : null}
          {search}
          {tabsRow}
        </>
      )}
      {browser.error ? (
        <Message theater={theater}>
          <Typography as="p" variant="body" color="danger" role="alert" m={0}>
            {browser.error}
          </Typography>
        </Message>
      ) : browser.loading ? (
        <BrowseSkeleton theater={theater} />
      ) : browser.items.length === 0 ? (
        <Message theater={theater}>
          <Typography as="p" variant="body" color="textMuted" m={0}>
            {browser.searching ? 'Nothing found.' : 'Nothing here.'}
          </Typography>
        </Message>
      ) : theater ? (
        // One row of large cards that scrolls sideways, with a thin quiet scrollbar. It is a size
        // container that asks for room for full-size cards and gives way when there is less, and the
        // cards then shrink to fit (see `CARD_SIZE`) instead of being cut off.
        <FadeScroll
          as="ul"
          gap={4}
          m={0}
          p={0}
          pb={2}
          css={{ ...THEATER_STAGE, listStyle: 'none', scrollSnapType: 'x proximity' }}
        >
          {browser.items.map((item, index) => (
            // Keyed by the list too, so another list arriving is new items fading in, not the same
            // ones left as they were; typing in the search does not stagger them.
            <li
              key={`${browser.listKey}:${item.id}`}
              css={{ flex: 'none', scrollSnapAlign: 'start' }}
            >
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
        // Rows scroll within the library, so what is above them (search, tabs) stays in view when
        // the room is short; with room to spare it is just as tall as its rows.
        <Flex
          direction="column"
          gap={1}
          as="ul"
          {...swipeProps}
          m={0}
          p={0}
          minHeight={0}
          css={{ listStyle: 'none', overflowY: 'auto', overflowX: 'hidden', touchAction: 'pan-y' }}
        >
          {browser.items.map((item, index) => (
            <li key={`${browser.listKey}:${item.id}`} css={{ flex: 'none' }}>
              <Reveal index={browser.searching ? 0 : index}>
                <BrowseRow
                  item={item}
                  active={activeOf(item)}
                  playing={nowPlaying}
                  onToggle={togglePlay}
                  asked={askedOf(item)}
                  queueable={queueable}
                  onOpen={() => openItem(item)}
                  onPlay={() => play(item)}
                  onNext={() => playNext(item)}
                  onAdd={() => addToQueue(item)}
                />
              </Reveal>
            </li>
          ))}
        </Flex>
      )}
    </Flex>
  );
}

/** What shows where the list will be while it loads: placeholders shaped like what is coming (cards in
 * the theater layout, rows in the list), in the room the real thing takes, so nothing moves. */
function BrowseSkeleton({ theater }: { theater: boolean }) {
  return theater ? (
    <Flex
      as="ul"
      role="status"
      aria-label="Loading"
      aria-busy="true"
      gap={4}
      m={0}
      p={0}
      pb={2}
      css={{ ...THEATER_STAGE, listStyle: 'none', overflow: 'hidden' }}
    >
      {Array.from({ length: 12 }, (_, index) => (
        <Flex
          as="li"
          key={index}
          direction="column"
          gap={2}
          css={{ flex: 'none', width: CARD_SIZE }}
        >
          <Skeleton width={CARD_SIZE} height={CARD_SIZE} radius="card" />
          <Flex direction="column" gap={1} px={1}>
            <Skeleton width="70%" height={14} />
            <Skeleton width="45%" height={12} />
          </Flex>
        </Flex>
      ))}
    </Flex>
  ) : (
    <Flex
      as="ul"
      role="status"
      aria-label="Loading"
      aria-busy="true"
      direction="column"
      gap={1}
      m={0}
      p={0}
      css={{ listStyle: 'none' }}
    >
      {Array.from({ length: 6 }, (_, index) => (
        <Flex as="li" key={index} align="center" gap={3} py={1.5} px={2}>
          <Skeleton width={44} height={44} />
          <Flex direction="column" gap={1.5} grow={1}>
            <Skeleton width="60%" height={14} />
            <Skeleton width="35%" height={12} />
          </Flex>
        </Flex>
      ))}
    </Flex>
  );
}

/** A line of text where the list would be (an error, nothing found), in the room the list takes in
 * the theater layout, so showing it does not change the library's height. */
function Message({ theater, children }: { theater: boolean; children: ReactNode }) {
  return theater ? (
    <Flex align="center" css={{ ...THEATER_STAGE, overflow: 'hidden' }}>
      {children}
    </Flex>
  ) : (
    <>{children}</>
  );
}

/** A labelled round button, for the actions of the album or playlist that is open. */
function PillButton({
  icon,
  label,
  onClick,
  primary,
  compact,
}: {
  icon: IconName;
  label: string;
  onClick: () => void;
  primary?: boolean;

  /** Only the icon, for a narrow space. */
  compact?: boolean;
}) {
  // Built like a tab (a button with the same padding), so the row they share does not change height.
  return (
    <Flex
      as="button"
      type="button"
      onClick={onClick}
      aria-label={label}
      title={label}
      align="center"
      justify="center"
      gap={1.5}
      height={CHIP_HEIGHT}
      px={3}
      radius="full"
      cursor="pointer"
      background={primary ? 'accent' : 'surfaceRaised'}
      color={primary ? 'accentText' : 'text'}
      css={{ flex: 'none', whiteSpace: 'nowrap' }}
    >
      <Icon name={icon} size={14} />
      {compact ? null : (
        <Typography as="span" variant="body">
          {label}
        </Typography>
      )}
    </Flex>
  );
}

function BrowseRow({
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
    <Flex align="center" gap={2}>
      <PlainButton
        align="center"
        gap={3}
        grow={1}
        minWidth={0}
        radius="row"
        py={1.5}
        px={2}
        cursor={main ? 'pointer' : 'default'}
        disabled={!main}
        aria-label={item.expandable ? `Open ${item.title}` : `Play ${item.title}`}
        onClick={main}
        css={({ palette }) => ({ '&:hover:not(:disabled)': { background: palette.surfaceRaised } })}
      >
        <Flex
          align="center"
          justify="center"
          background="surfaceRaised"
          color="textMuted"
          radius="small"
          width={44}
          height={44}
          overflow="hidden"
          position="relative"
          css={{ flex: 'none' }}
        >
          {item.artworkUrl ? (
            <Cover src={item.artworkUrl} size="small" />
          ) : (
            <Icon name={KIND_ICONS[item.kind]} size={20} />
          )}
          {asked.includes('playItem') ? <Waiting size={20} /> : null}
        </Flex>
        <Flex direction="column" minWidth={0} grow={1}>
          <Typography as="span" variant="bodyStrong" {...(active ? { color: 'accent' } : {})}>
            <MarqueeText>{item.title}</MarqueeText>
          </Typography>
          {item.subtitle ? (
            <Typography as="span" variant="secondary" color="textMuted">
              <MarqueeText>{item.subtitle}</MarqueeText>
            </Typography>
          ) : null}
        </Flex>
        {item.expandable ? <Opens /> : null}
      </PlainButton>
      {active ? (
        <>
          {/* The room the other buttons take is kept, empty, so the text and the chevron line up with
              the rows around it. */}
          {Array.from({ length: spare }, (_, slot) => (
            <Box key={slot} aria-hidden="true" css={{ visibility: 'hidden' }}>
              <IconButton icon="lu:play" label="" glyph={16} disabled />
            </Box>
          ))}
          <IconButton
            icon={playing ? 'lu:pause' : 'lu:play'}
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
            icon="lu:list-start"
            label={`Play ${item.title} next`}
            glyph={16}
            disabled={asked.includes('queueNext')}
            feedback={asked.includes('queueNext') ? 'pending' : undefined}
            onClick={onNext}
          />
          <IconButton
            icon="lu:list-plus"
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
          icon="lu:play"
          label={`Play ${item.title}`}
          glyph={16}
          disabled={asked.includes('playItem')}
          feedback={asked.includes('playItem') ? 'pending' : undefined}
          onClick={onPlay}
        />
      ) : null}
    </Flex>
  );
}

/** The room the theater row takes, whatever it holds: it asks for full-size cards and gives way when
 * there is less, and is a size container so the cards can shrink to fit. What stands in for the row
 * while it loads, or when it is empty, takes the same room, so the library does not change height. */
const THEATER_STAGE = {
  containerType: 'size',
  flex: '0 1 272px',
  minHeight: 192,
} as const;

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
    <Box position="relative" css={{ width: CARD_SIZE }}>
      <PlainButton
        direction="column"
        gap={2}
        width="100%"
        cursor={main ? 'pointer' : 'default'}
        disabled={!main}
        aria-label={item.expandable ? `Open ${item.title}` : `Play ${item.title}`}
        onClick={main}
      >
        <Flex
          align="center"
          justify="center"
          background="surfaceRaised"
          color="textMuted"
          radius={item.kind === 'artist' ? 'full' : 'card'}
          overflow="hidden"
          position="relative"
          css={{ width: CARD_SIZE, height: CARD_SIZE }}
        >
          {item.artworkUrl ? (
            <Cover src={item.artworkUrl} size="medium" />
          ) : (
            <Icon name={KIND_ICONS[item.kind]} size={56} />
          )}
          {asked.includes('playItem') ? <Waiting size={32} /> : null}
        </Flex>
        <Flex direction="column" minWidth={0} px={1}>
          <Flex align="center" gap={1} minWidth={0}>
            <Typography
              as="span"
              variant="bodyStrong"
              css={{ minWidth: 0 }}
              {...(active ? { color: 'accent' } : {})}
            >
              <MarqueeText>{item.title}</MarqueeText>
            </Typography>
            {item.expandable ? <Opens /> : null}
          </Flex>
          {item.subtitle ? (
            <Typography as="span" variant="secondary" color="textMuted">
              <MarqueeText>{item.subtitle}</MarqueeText>
            </Typography>
          ) : null}
        </Flex>
      </PlainButton>
      {active ? (
        <Flex position="absolute" css={{ right: 10, top: `calc(${CARD_SIZE} - 54px)` }}>
          <IconButton
            icon={playing ? 'lu:pause' : 'lu:play'}
            label={playing ? `Pause ${item.title}` : `Resume ${item.title}`}
            glyph={16}
            primary
            onClick={onToggle}
          />
        </Flex>
      ) : item.playable && (queueable || item.expandable) ? (
        // Over the artwork's lower right corner: add to the queue, and play all of an album or playlist.
        <Flex
          align="center"
          gap={1}
          position="absolute"
          css={{ right: 10, top: `calc(${CARD_SIZE} - 54px)` }}
        >
          {queueable ? (
            <IconButton
              icon="lu:list-plus"
              label={`Add ${item.title} to the queue`}
              glyph={16}
              disabled={asked.includes('queueAdd')}
              feedback={asked.includes('queueAdd') ? 'pending' : undefined}
              onClick={onAdd}
            />
          ) : null}
          {item.expandable ? (
            <IconButton
              icon="lu:play"
              label={`Play ${item.title}`}
              glyph={16}
              disabled={asked.includes('playItem')}
              feedback={asked.includes('playItem') ? 'pending' : undefined}
              onClick={onPlay}
            />
          ) : null}
        </Flex>
      ) : null}
    </Box>
  );
}

/** Over an item's artwork while the player has not answered a request to play it. */
function Waiting({ size }: { size: number }) {
  return (
    <Flex
      align="center"
      justify="center"
      position="absolute"
      css={({ palette }) => ({
        inset: 0,
        color: palette.text,
        // The artwork is dimmed by a layer of the page's own color, under the spinner.
        '&::before': {
          content: '""',
          position: 'absolute',
          inset: 0,
          background: palette.bg,
          opacity: 0.6,
        },
      })}
    >
      <Box position="relative" css={{ display: 'grid' }}>
        <Spinner size={size} />
      </Box>
    </Flex>
  );
}

/** Marks something that opens (an album, a playlist, an artist, a folder) and is not played by a tap, as against a track. */
function Opens() {
  return (
    <Box color="textMuted" css={{ flex: 'none', display: 'grid' }} aria-hidden="true">
      <Icon name="lu:chevron-right" size={16} />
    </Box>
  );
}

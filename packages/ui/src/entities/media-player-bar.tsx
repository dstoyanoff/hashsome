/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import type { CSSObject } from '@emotion/react';
import { Flex, Typography } from '../style/prim.tsx';
import { motion } from 'motion/react';
import { useState, type ReactNode } from 'react';
import { Icon } from '../icon.tsx';
import { ValueBar } from '../layout/drawer-controls.tsx';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import { HoldProgress } from '../layout/hold-progress.tsx';
import { useHold } from '../layout/use-hold.ts';
import { IconButton } from '../layout/tile.tsx';
import { statusLabels } from '../status.ts';
import { usePressFeedback, useTransportAvailability, useVolumeControl } from './media-controls.ts';
import { Cover } from '../layout/cover.tsx';
import { Spinner } from '../layout/spinner.tsx';
import { MarqueeText } from '../layout/marquee-text.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { MediaBrowser } from './media-browser.tsx';
import { LoadingLine, useLoadingLabel } from './media-loading-line.tsx';
import { MediaPlayerFull } from './media-player-full.tsx';
import { MediaQueue } from './media-queue.tsx';
import { SpeakersOverlay } from './media-speakers-pill.tsx';
import { formatDuration, useMediaPosition, useSteadyPlaying } from './media-progress.ts';
import { useSeekHold } from './media-seek.ts';
import { SeekLine } from './seek-line.tsx';

export interface MediaPlayerBarProps {
  /** A media player, as a ref like `ha:media_player.living_room` or `ma:living_room`, or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'mediaPlayer'>;

  /** What to call the player: the drawer's title and the name shown when nothing is playing. Defaults to the player's own name. */
  name?: string;

  /** Content for the media browser shown with the player in the drawer. Holding the card or pressing the artwork opens the drawer; the browse button opens it expanded. By default a `MediaBrowser` over the player's own library, shown only for a ref whose player has one; pass your own content to replace it, or `false` for no browse button. */
  browse?: ReactNode | false;

  /** `false` builds no drawer: holding the card, pressing the artwork and the browse button then do nothing, which is lighter on a small display. Default `true`. See `onOpen` to send them somewhere else instead. */
  drawer?: boolean;

  /** Called instead of opening the drawer when the card is held, the artwork pressed or the browse button pressed: for a dashboard with a page of its own for the player (a `MediaPlayerFull`), `onOpen={() => navigate('/bathroom/music')}`. Given, no drawer is built. The browse button stays unless `browse` is `false`. */
  onOpen?: () => void;

  /** The library and the queue each open as a full-size overlay of their own, from the browse button and a new queue button, instead of one drawer with the whole player in it: for a small display, where the card itself is the player. The library overlay is a `MediaBrowser` as a list (or your `browse` content), the queue overlay a `MediaQueue`; each button is left out when the player has none, or for `browse={false}`. Holding the card and pressing the artwork then do nothing, unless `onOpen` is given. Default `false`. */
  overlays?: boolean;

  /** Whether the player can add speakers to what it plays, put it back to just itself and join a stream playing elsewhere (`MediaSpeakers`), from a speakers button with `overlays` and a Speakers tab in the drawer. Only for a player that can be grouped. `false` leaves it out. Default `true`. */
  grouping?: boolean;

  /** An allowlist of the speakers that can be added: only those that are in it and that the player can be grouped with are offered. Absent, every speaker it can be grouped with is offered. */
  speakers?: EntityRef[];

  /** `1` puts the controls beside the track, in one pill; `2` puts them on a second row under it, for a narrow space (a small wall display). Default `'auto'`: one row, and two once the bar is narrower than 560 px wide. */
  rows?: 1 | 2 | 'auto';

  /** `false` leaves the time ("1:23 / 3:45") out, for a bar with no room for it: the ring round the artwork and the line along the bottom still show how far along playback is. Default `true`. */
  time?: boolean;

  /** `false` leaves the shuffle button out, for a bar with no room for it (a small wall display). Default `true`, shown when the player has shuffle. */
  shuffle?: boolean;
}

/** Narrower than this, a bar with `rows="auto"` is two rows. */
const TWO_ROWS_BELOW = 560;

/** Glyph size inside the player's 44px circles — smaller than an icon's default so there is breathing room around it. */
const MEDIA_GLYPH = 18;

/** The ring around the artwork (the size of an icon circle): a button when there is somewhere to
 * go, otherwise just the ring. `fraction` (0 to 1) fills it with how far along playback is, from the
 * top, like the large player's ring. */
function Ring({
  onOpen,
  fraction,
  children,
}: {
  onOpen?: (() => void) | undefined;
  fraction?: number | undefined;
  children: ReactNode;
}) {
  return (
    <Flex
      {...(onOpen
        ? {
            as: 'button',
            type: 'button',
            'aria-label': 'Open player',
            title: 'Open player',
            onClick: onOpen,
            cursor: 'pointer',
          }
        : { as: 'span' })}
      data-part="ring"
      center
      position="relative"
      p={0}
      radius="full"
      css={({ density }) => ({
        width: density.iconCircle,
        height: density.iconCircle,
        flex: 'none',
        background: 'none',
      })}
    >
      <svg
        viewBox="0 0 100 100"
        aria-hidden="true"
        css={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
        }}
      >
        <circle
          cx="50"
          cy="50"
          r="47.5"
          fill="none"
          strokeWidth="4"
          css={({ palette }) => ({ stroke: palette.border })}
        />
        {fraction !== undefined ? (
          <circle
            data-part="progress"
            cx="50"
            cy="50"
            r="47.5"
            fill="none"
            strokeWidth="4"
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={`${fraction * 100} 100`}
            transform="rotate(-90 50 50)"
            css={({ palette }) => ({ stroke: palette.accent })}
          />
        ) : null}
      </svg>
      {children}
    </Flex>
  );
}

/** The same spring the swatch rows pop in with, so the volume overlay opens like they do. */
const VOLUME_SPRING = { type: 'spring', stiffness: 520, damping: 26 } as const;

/** What the pill is like in two rows: the artwork, "title · artist" and the time on top; under them
 * the buttons share the whole width, all of one size, and while the volume slider is open it has the
 * row to itself, with only the button that closes it. The bar is as tall as two regular tiles and
 * the gap between them (a tile is its icon circle and a spacing unit), so it sits in a grid of
 * tiles. A line break between the rows comes from an empty item that fills a line of its own. */
const stacked = ({
  radius,
  spacing,
  density,
}: {
  radius: { card: string };
  spacing: (n: number) => string;
  density: { iconCircle: number; space: number };
}): CSSObject => ({
  display: 'flex',
  flexWrap: 'wrap',
  alignItems: 'center',
  alignContent: 'center',
  columnGap: spacing(2.5),
  rowGap: 0,
  minHeight: 2 * (density.iconCircle + density.space / 3) + density.space,
  paddingTop: spacing(2.5),
  paddingBottom: spacing(2.5),
  borderRadius: radius.card,
  '&::before': { content: '""', order: 1, flexBasis: '100%', height: 0 },
  '& > [data-part="track"]': {
    flex: '1 1 0',
    minWidth: 0,
    flexDirection: 'row',
    alignItems: 'baseline',
    gap: spacing(1.5),
    // The title and the artist share the line and shorten, with an ellipsis, before they run into
    // the time; the artist follows the title after a dot.
    '& > :nth-child(1)': {
      flex: '0 1 auto',
      minWidth: 0,
      // The title is what gives way, not the shuffle button beside it.
      '& > :first-child': { minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' },
    },
    '& > :nth-child(2)': {
      flex: '0 1 auto',
      minWidth: 0,
      overflow: 'hidden',
      '&::before': { content: '"·"', marginRight: spacing(1.5) },
    },
  },
  '& [data-part="tools"], & [data-part="transport"]': { display: 'contents' },
  '& [data-part="tools"] > button, & [data-part="transport"] > button': {
    order: 2,
    flex: '1 1 0',
    minWidth: 0,
    width: 'auto',
    height: 34,
    marginTop: spacing(2.5),
  },
  '& [data-part="transport"] > button': { order: 4 },
  '& > [data-part="volume"]': {
    order: 3,
    flex: '1 1 0',
    minWidth: 0,
    marginTop: spacing(2.5),
  },
  '&[data-volume-open="true"]': {
    '& > [data-part="track"], & > [data-part="time"]': { display: 'flex' },
    // The slider has the row: only the button that closes it stays, a circle beside it.
    '& [data-part="tools"] > button:nth-of-type(n + 2), & [data-part="transport"] > button': {
      display: 'none',
    },
    '& [data-part="tools"] > button:first-of-type': { flex: 'none', width: 34 },
  },
});

/** The bar itself. Holding it (anywhere but on a button or slider) opens the drawer, with a thin
 * line along the top that fills while the press is timed, like a tile's. */
function HoldCard({
  open,
  status,
  rows,
  volumeOpen,
  children,
}: {
  open: (() => void) | undefined;
  status: string;
  rows: 1 | 2 | 'auto';
  volumeOpen: boolean;
  children: ReactNode;
}) {
  const hold = useHold(() => open?.(), open !== undefined);
  const card = (
    <Flex
      position="relative"
      align="center"
      grow={1}
      minWidth={0}
      gap={3}
      pl={2.25}
      pr={3}
      background="surface"
      radius="full"
      overflow="hidden"
      data-grid-card
      data-status={status}
      data-rows={rows === 2 ? 2 : undefined}
      data-volume-open={volumeOpen}
      css={(theme) => ({
        minHeight: theme.density.tileHeight,
        // In one row the volume slider takes the track's place; in two it has its own row.
        '&[data-volume-open="true"] > [data-part="track"], &[data-volume-open="true"] > [data-part="time"]':
          { display: 'none' },
        ...(rows === 2 ? stacked(theme) : {}),
        ...(rows === 'auto'
          ? { [`@container (max-width: ${TWO_ROWS_BELOW - 1}px)`]: stacked(theme) }
          : {}),
      })}
      {...hold.handlers}
    >
      {children}
      {hold.holding ? <HoldProgress /> : null}
    </Flex>
  );

  // `auto` asks the width the bar is given, so its parent is the thing measured.
  return rows === 'auto' ? (
    <Flex width="100%" css={{ containerType: 'inline-size' }}>
      {card}
    </Flex>
  ) : (
    card
  );
}

/** Now playing with previous / play-pause / next and a volume control, for a media player entity. */
export function MediaPlayerBar({
  entity,
  name,
  browse,
  drawer = true,
  onOpen,
  overlays = false,
  grouping = true,
  speakers,
  rows = 'auto',
  time = true,
  shuffle = true,
}: MediaPlayerBarProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const player = handle.entity;
  const status = handle.status;
  const [volumeOpen, setVolumeOpen] = useState(false);
  const loading = useLoadingLabel(player?.ref);
  const ready = status === 'ready';
  const playing = useSteadyPlaying(player);
  const muted = player?.muted === true;
  const caps = player?.capabilities;
  const { feedbackFor, press } = usePressFeedback(player?.ref);
  const volume = useVolumeControl(handle);

  const artwork = (
    <Flex
      as="span"
      center
      width={30}
      height={30}
      radius="full"
      overflow="hidden"
      background="surfaceRaised"
      color="textMuted"
    >
      {player?.media?.artworkUrl ? (
        <Cover src={player.media.artworkUrl} size="small" />
      ) : (
        <Icon name="lu:music" size={14} />
      )}
    </Flex>
  );

  const position = useMediaPosition(player);
  const can = useTransportAvailability(player, position);
  const seek = useSeekHold(
    ready ? position : undefined,
    caps?.seek ? (next) => void handle.command('seek', { position: next }) : undefined,
  );

  const duration = ready ? player?.duration : undefined;
  const timed = seek.shown !== undefined && duration !== undefined && duration > 0;

  // How far along playback is, for the ring round the artwork.
  const progress =
    timed && duration !== undefined
      ? Math.min(1, Math.max(0, (seek.shown ?? 0) / duration))
      : undefined;

  const ref = typeof entity === 'string' ? entity : undefined;
  const browser =
    browse === false
      ? undefined
      : (browse ?? (ref && caps?.browse ? <MediaBrowser entity={ref} layout="auto" /> : undefined));

  // What the overlays hold, when the library and the queue open on their own (`overlays`).
  const libraryBody =
    browse === false
      ? undefined
      : (browse ?? (ref && caps?.browse ? <MediaBrowser entity={ref} layout="list" /> : undefined));

  const queueBody = ref && caps?.queue === true ? <MediaQueue entity={ref} /> : undefined;
  // And whether it can be grouped, for the pill that opens who plays together with it.
  const grouped = grouping && ref !== undefined && caps?.group === true;

  const volumeSlider = (
    <Flex
      as={motion.div}
      align="center"
      gap={2}
      grow={1}
      minWidth={0}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.15 }}
      data-part="volume"
    >
      <PlainButton
        as={motion.button}
        initial={{ scale: 0, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={VOLUME_SPRING}
        aria-label={muted ? 'Unmute' : 'Mute'}
        aria-pressed={muted}
        title={muted ? 'Unmute' : 'Mute'}
        onClick={volume.toggleMute}
        center
        color={muted ? 'accent' : 'textMuted'}
        css={{ flex: 'none' }}
      >
        <Icon name={muted ? 'lu:volume-x' : 'lu:volume-2'} size={16} />
      </PlainButton>
      <Flex
        as={motion.div}
        grow={1}
        minWidth={0}
        initial={{ scaleX: 0.4, opacity: 0 }}
        animate={{ scaleX: 1, opacity: 1 }}
        transition={{ ...VOLUME_SPRING, delay: 0.04 }}
        css={{ transformOrigin: 'left center' }}
      >
        <ValueBar
          label="Volume level"
          value={volume.shown}
          min={0}
          max={100}
          keyStep={5}
          height={10}
          onDrag={volume.drag}
          onCommit={volume.commit}
        />
      </Flex>
      <Typography
        as={motion.span}
        variant="secondary"
        color="textMuted"
        minWidth={32}
        align="right"
        css={{ flex: 'none' }}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.2, delay: 0.1 }}
      >
        {volume.shown}%
      </Typography>
    </Flex>
  );

  const trackInfo = (
    <>
      <Flex
        data-part="track"
        direction="column"
        grow={1}
        minWidth={0}
        {...(ready ? {} : { color: 'textMuted' as const })}
      >
        <Flex align="center" gap={1.5} minWidth={0}>
          <Typography as="span" variant="label" css={{ minWidth: 0 }}>
            <MarqueeText>
              {ready
                ? (player?.media?.title ?? 'Nothing playing')
                : statusLabels[status as Exclude<typeof status, 'ready'>]}
            </MarqueeText>
          </Typography>
          {ready && shuffle && caps?.shuffle ? (
            <PlainButton
              aria-label="Shuffle"
              aria-pressed={player?.shuffle === true}
              title="Shuffle"
              onClick={() =>
                press('setShuffle', () =>
                  handle.command('setShuffle', { shuffle: player?.shuffle !== true }),
                )
              }
              center
              color={player?.shuffle === true ? 'accent' : 'textMuted'}
              css={{ flex: 'none' }}
            >
              {feedbackFor('setShuffle') === 'pending' ? (
                <Spinner size={14} />
              ) : (
                <Icon name="lu:shuffle" size={14} />
              )}
            </PlainButton>
          ) : null}
        </Flex>
        {loading ? (
          <LoadingLine label={loading} variant="secondary" />
        ) : ready && player?.media?.artist ? (
          <Typography as="span" variant="secondary" color="textMuted">
            <MarqueeText>{player.media.artist}</MarqueeText>
          </Typography>
        ) : null}
      </Flex>
    </>
  );

  const bar = (open?: () => void, openExpanded?: () => void, openQueue?: () => void) => (
    <HoldCard open={open} status={status} rows={rows} volumeOpen={volumeOpen && ready}>
      {/* A ring (the old artwork size) around the artwork, which is as tall as the title + artist
          lines beside it. */}
      <Ring onOpen={open} fraction={progress}>
        {artwork}
      </Ring>
      {trackInfo}
      {/* The time sits at the vertical middle of the bar, beside the track; in two rows it is at the
          right of the top row. The volume slider takes the track's and the time's place in one row. */}
      {timed && time ? (
        <Typography
          as="span"
          data-part="time"
          variant="secondary"
          color="textMuted"
          noWrap
          css={{ flex: 'none', fontVariantNumeric: 'tabular-nums' }}
        >
          {formatDuration(seek.shown ?? 0)} / {formatDuration(duration ?? 0)}
        </Typography>
      ) : null}
      {volumeOpen && ready ? volumeSlider : null}
      <Flex data-part="tools" align="center" gap={3} css={{ flex: 'none' }}>
        <IconButton
          icon={volumeOpen ? 'lu:x' : muted ? 'lu:volume-x' : 'lu:volume-2'}
          label={volumeOpen ? 'Close volume' : 'Volume'}
          glyph={MEDIA_GLYPH}
          disabled={!ready || (caps?.volume === false && caps.mute === false)}
          onClick={() => setVolumeOpen((current) => !current)}
        />
        {(
          overlays
            ? openExpanded !== undefined
            : open && (onOpen ? browse !== false : browser !== undefined)
        ) ? (
          <IconButton
            icon="lu:library"
            label="Browse media"
            glyph={MEDIA_GLYPH}
            onClick={() => (openExpanded ?? open)?.()}
          />
        ) : null}
        {openQueue ? (
          <IconButton icon="lu:list-music" label="Queue" glyph={MEDIA_GLYPH} onClick={openQueue} />
        ) : null}
        {grouped && ref && ready ? (
          <SpeakersOverlay
            player={player}
            entity={ref}
            name={name}
            title={name ?? player?.name ?? fallbackName(entity)}
            allowed={speakers}
            compact
          />
        ) : null}
      </Flex>
      <Flex data-part="transport" align="center" gap={3} css={{ flex: 'none' }}>
        <IconButton
          icon="lu:skip-back"
          label="Previous"
          glyph={MEDIA_GLYPH}
          disabled={!ready || caps?.previous === false || !can.previous}
          feedback={feedbackFor('previous')}
          onClick={() => press('previous', () => handle.command('previous'))}
        />
        <IconButton
          icon={playing ? 'lu:pause' : 'lu:play'}
          label={playing ? 'Pause' : 'Play'}
          glyph={MEDIA_GLYPH}
          disabled={!ready || !can.play}
          primary
          feedback={feedbackFor('togglePlay')}
          onClick={() => press('togglePlay', () => handle.command('togglePlay'))}
        />
        <IconButton
          icon="lu:skip-forward"
          label="Next"
          glyph={MEDIA_GLYPH}
          disabled={!ready || caps?.next === false || !can.next}
          feedback={feedbackFor('next')}
          onClick={() => press('next', () => handle.command('next'))}
        />
      </Flex>
      {timed ? (
        <SeekLine seek={seek} duration={duration ?? 0} seekable={caps?.seek === true} />
      ) : null}
    </HoldCard>
  );

  // Holding the card, the artwork and the browse button open the same drawer: the player laid out
  // like the vertical one, with the library below it.
  if (!ready) {
    return bar();
  }

  // The library and the queue are overlays of their own; the card is the player.
  if (overlays) {
    const title = name ?? player?.name ?? fallbackName(entity);
    return (
      <DrawerTrigger icon="lu:library" label="Library" kind={title} body={libraryBody ?? null}>
        {(_open, _expanded, openLibrary) => (
          <DrawerTrigger icon="lu:list-music" label="Queue" kind={title} body={queueBody ?? null}>
            {(_openToo, _expandedToo, openQueue) =>
              bar(onOpen, libraryBody ? openLibrary : undefined, queueBody ? openQueue : undefined)
            }
          </DrawerTrigger>
        )}
      </DrawerTrigger>
    );
  }

  // Held, pressed or browsed, the player goes where the dashboard says instead of opening a drawer.
  if (onOpen) {
    return bar(onOpen, onOpen);
  }

  if (!drawer) {
    return bar();
  }

  return (
    <DrawerTrigger
      icon="lu:music"
      label={name ?? player?.name ?? fallbackName(entity)}
      kind="Media"
      body={
        <MediaPlayerFull
          entity={entity}
          {...(name !== undefined ? { name } : {})}
          {...(browser !== undefined ? { browser } : {})}
          {...(typeof entity === 'string' ? { queue: <MediaQueue entity={entity} /> } : {})}
          grouping={grouping}
          speakersView="inline"
          {...(speakers ? { speakers } : {})}
        />
      }
    >
      {(open, openExpanded) => bar(open, openExpanded)}
    </DrawerTrigger>
  );
}

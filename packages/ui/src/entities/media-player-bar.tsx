import type { EntityRef } from '@hashsome/core';
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
import { LoadingLine, useLoadingLabel } from './media-loading-line.tsx';
import { SpeakersOverlay } from './media-speakers-pill.tsx';
import { formatDuration, useMediaPosition, useSteadyPlaying } from './media-progress.ts';
import { useSeekHold } from './media-seek.ts';
import { MediaBrowser, MediaPlayerFull, MediaQueue } from './media-lazy.ts';
import styles from './media-player-bar.module.css';
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
  const content = (
    <>
      <svg viewBox="0 0 100 100" aria-hidden="true" className={styles.ringSvg}>
        <circle className={styles.ringTrack} cx="50" cy="50" r="47.5" fill="none" strokeWidth="4" />
        {fraction !== undefined ? (
          <circle
            className={styles.ringProgress}
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
          />
        ) : null}
      </svg>
      {children}
    </>
  );

  return onOpen ? (
    <button
      type="button"
      className={styles.ring}
      data-part="ring"
      aria-label="Open player"
      title="Open player"
      onClick={onOpen}
    >
      {content}
    </button>
  ) : (
    <span className={styles.ring} data-part="ring">
      {content}
    </span>
  );
}

/** The same spring the swatch rows pop in with, so the volume overlay opens like they do. */
const VOLUME_SPRING = { type: 'spring', stiffness: 520, damping: 26 } as const;

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
    <div
      className={styles.card}
      data-grid-card
      data-status={status}
      data-rows={rows === 1 ? undefined : rows}
      data-volume-open={volumeOpen}
      {...hold.handlers}
    >
      {children}
      {hold.holding ? <HoldProgress /> : null}
    </div>
  );

  // `auto` asks the width the bar is given, so its parent is the thing measured.
  return rows === 'auto' ? <div className={styles.wrap}>{card}</div> : card;
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
    <span className={styles.artwork}>
      {player?.media?.artworkUrl ? (
        <Cover src={player.media.artworkUrl} size="small" />
      ) : (
        <Icon name="lu:music" size={14} />
      )}
    </span>
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
    <motion.div
      className={styles.volume}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.15 }}
      data-part="volume"
    >
      <PlainButton
        as={motion.button}
        className={styles.mute}
        initial={{ scale: 0, opacity: 0 }}
        animate={{ scale: 1, opacity: 1 }}
        transition={VOLUME_SPRING}
        aria-label={muted ? 'Unmute' : 'Mute'}
        aria-pressed={muted}
        title={muted ? 'Unmute' : 'Mute'}
        onClick={volume.toggleMute}
      >
        <Icon name={muted ? 'lu:volume-x' : 'lu:volume-2'} size={16} />
      </PlainButton>
      <motion.div
        className={styles.slider}
        initial={{ scaleX: 0.4, opacity: 0 }}
        animate={{ scaleX: 1, opacity: 1 }}
        transition={{ ...VOLUME_SPRING, delay: 0.04 }}
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
      </motion.div>
      <motion.span
        className={styles.percent}
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.2, delay: 0.1 }}
      >
        {volume.shown}%
      </motion.span>
    </motion.div>
  );

  const trackInfo = (
    <div className={styles.track} data-part="track" data-ready={ready}>
      <div className={styles.titleRow}>
        <span className={styles.title}>
          <MarqueeText>
            {ready
              ? (player?.media?.title ?? 'Nothing playing')
              : statusLabels[status as Exclude<typeof status, 'ready'>]}
          </MarqueeText>
        </span>
        {ready && shuffle && caps?.shuffle ? (
          <PlainButton
            className={styles.shuffle}
            aria-label="Shuffle"
            aria-pressed={player?.shuffle === true}
            title="Shuffle"
            onClick={() =>
              press('setShuffle', () =>
                handle.command('setShuffle', { shuffle: player?.shuffle !== true }),
              )
            }
          >
            {feedbackFor('setShuffle') === 'pending' ? (
              <Spinner size={14} />
            ) : (
              <Icon name="lu:shuffle" size={14} />
            )}
          </PlainButton>
        ) : null}
      </div>
      {loading ? (
        <LoadingLine label={loading} variant="secondary" />
      ) : ready && player?.media?.artist ? (
        <span className={styles.artist}>
          <MarqueeText>{player.media.artist}</MarqueeText>
        </span>
      ) : null}
    </div>
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
        <span className={styles.time} data-part="time">
          {formatDuration(seek.shown ?? 0)} / {formatDuration(duration ?? 0)}
        </span>
      ) : null}
      {volumeOpen && ready ? volumeSlider : null}
      <div className={styles.tools} data-part="tools">
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
      </div>
      <div className={styles.transport} data-part="transport">
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
      </div>
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

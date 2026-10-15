/** @jsxImportSource @emotion/react */
import { useTheme } from '@emotion/react';
import { Flex, Grid, Typography } from 'e-prim';
import type { ReactNode } from 'react';
import type { EntityHandle } from '../entity-handle.ts';
import { Icon } from '../icon.tsx';
import { ValueBar } from '../layout/drawer-controls.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { IconButton } from '../layout/tile.tsx';
import { statusLabels } from '../status.ts';
import { usePressFeedback, useTransportAvailability, useVolumeControl } from './media-controls.ts';
import { formatDuration, useMediaPosition, useSteadyPlaying } from './media-progress.ts';
import { useSeekHold } from './media-seek.ts';
import { ArtworkRing } from './artwork-ring.tsx';
import { LoadingLine, useLoadingLabel } from './media-loading-line.tsx';

/** The upright "now playing" panel the media column and page share: the artwork on a record, what is playing, how
 * far along it is, the transport buttons and a volume bar that is always there. Not exported from
 * the package; `MediaPlayerColumn` and `MediaPlayerFull` are its public forms. */
export function NowPlaying({
  handle,
  fallback,
  name,
  extra,
  leading,
  speakers,
  onOpenArtwork,
  size,
  shuffle: shufflePlacement = 'transport',
}: {
  handle: EntityHandle<'mediaPlayer'>;

  /** What to call the player before it reports a name. */
  fallback: string;

  /** Calls the player this instead of the name it reports. */
  name?: string;

  /** One more button, to the right of the transport (the browse button). Shuffle takes the slot on the left, unless it sits by the title. */
  extra?: ReactNode;

  /** One more button, to the left of the transport, in the slot shuffle has when it sits by the title. Not shown while shuffle is in the row. */
  leading?: ReactNode;

  /** What goes under the time, above the buttons: the speakers pill. */
  speakers?: ReactNode;

  /** Makes the artwork a button that calls this (opens the library). */
  onOpenArtwork?: () => void;

  /** The artwork ring's diameter in px, for a roomier player. Default 168. */
  size?: number;

  /** Where the shuffle button goes: `transport` is the left slot of the button row, `title` a small button beside the song title, which leaves previous, play and next centered on their own. Default `transport`. */
  shuffle?: 'transport' | 'title';
}) {
  const player = handle.entity;
  const { iconCircle, space } = useTheme().density;
  const transportWidth = 5 * iconCircle + 4 * space;
  const { status } = handle;
  const loading = useLoadingLabel(player?.ref);
  const ready = status === 'ready';
  const caps = player?.capabilities;
  const playing = useSteadyPlaying(player);
  const { feedbackFor, press } = usePressFeedback(player?.ref);
  const volume = useVolumeControl(handle);
  const position = useMediaPosition(player);
  const can = useTransportAvailability(player, position);
  const duration = ready ? player?.duration : undefined;
  // The ring and the time under the title share one position, so dragging the ring moves both.
  const seek = useSeekHold(
    ready ? position : undefined,
    caps?.seek ? (next) => void handle.command('seek', { position: next }) : undefined,
  );

  return (
    <Flex direction="column" gap={4} data-status={status} css={{ minHeight: 0 }}>
      <ArtworkRing
        artworkUrl={player?.media?.artworkUrl}
        seek={seek}
        duration={duration}
        seekable={caps?.seek === true}
        onOpen={onOpenArtwork}
        {...(size !== undefined ? { size } : {})}
        // No wider than the row of five buttons under it (268px: five circles and the gaps between them).
        maxSize={transportWidth}
      />
      <Flex direction="column" align="center" gap={0.5} color={ready ? 'text' : 'textMuted'}>
        {/* In the drawer, shuffle sits by the title, so the transport below is just previous, play and next. */}
        <Flex align="center" justify="center" gap={2} maxWidth="100%">
          <Typography
            as="h2"
            variant="title"
            noWrap
            textOverflow="ellipsis"
            overflow="hidden"
            m={0}
            minWidth={0}
          >
            {ready
              ? (player?.media?.title ?? 'Nothing playing')
              : statusLabels[status as Exclude<typeof status, 'ready'>]}
          </Typography>
          {caps?.shuffle && shufflePlacement === 'title' ? (
            <IconButton
              icon="lu:shuffle"
              label="Shuffle"
              size={14}
              active={player?.shuffle === true}
              pressed={player?.shuffle === true}
              disabled={!ready}
              feedback={feedbackFor('setShuffle')}
              onClick={() =>
                press('setShuffle', () =>
                  handle.command('setShuffle', { shuffle: player?.shuffle !== true }),
                )
              }
            />
          ) : null}
        </Flex>
        {loading ? (
          <LoadingLine label={loading} variant="body" />
        ) : ready && player?.media?.artist ? (
          <Typography
            as="span"
            variant="body"
            color="textMuted"
            noWrap
            textOverflow="ellipsis"
            overflow="hidden"
          >
            {[player.media.artist, player.media.album].filter(Boolean).join(' · ')}
          </Typography>
        ) : (
          <Typography
            as="span"
            variant="body"
            color="textMuted"
            noWrap
            textOverflow="ellipsis"
            overflow="hidden"
          >
            {name ?? player?.name ?? fallback}
          </Typography>
        )}
      </Flex>
      {seek.shown !== undefined && duration ? (
        <Typography
          as="span"
          variant="secondary"
          color="textMuted"
          align="center"
          css={{ fontVariantNumeric: 'tabular-nums' }}
        >
          {formatDuration(seek.shown)} / {formatDuration(duration)}
        </Typography>
      ) : null}
      {speakers ? (
        <Flex justify="center" css={{ marginTop: -4 }}>
          {speakers}
        </Flex>
      ) : null}
      {/* Five equal slots, so play/pause stays in the middle whichever side buttons there are. */}
      <Grid
        gap={3}
        css={({ density }) => ({
          gridTemplateColumns: `repeat(5, ${density.iconCircle}px)`,
          justifyContent: 'center',
        })}
      >
        {caps?.shuffle && shufflePlacement === 'transport' ? (
          <IconButton
            icon="lu:shuffle"
            label="Shuffle"
            glyph={18}
            active={player?.shuffle === true}
            pressed={player?.shuffle === true}
            disabled={!ready}
            feedback={feedbackFor('setShuffle')}
            onClick={() =>
              press('setShuffle', () =>
                handle.command('setShuffle', { shuffle: player?.shuffle !== true }),
              )
            }
          />
        ) : shufflePlacement === 'title' ? (
          (leading ?? <span />)
        ) : (
          <span />
        )}
        <IconButton
          icon="lu:skip-back"
          label="Previous"
          glyph={18}
          disabled={!ready || caps?.previous === false || !can.previous}
          feedback={feedbackFor('previous')}
          onClick={() => press('previous', () => handle.command('previous'))}
        />
        <IconButton
          icon={playing ? 'lu:pause' : 'lu:play'}
          label={playing ? 'Pause' : 'Play'}
          glyph={18}
          disabled={!ready || !can.play}
          primary
          feedback={feedbackFor('togglePlay')}
          onClick={() => press('togglePlay', () => handle.command('togglePlay'))}
        />
        <IconButton
          icon="lu:skip-forward"
          label="Next"
          glyph={18}
          disabled={!ready || caps?.next === false || !can.next}
          feedback={feedbackFor('next')}
          onClick={() => press('next', () => handle.command('next'))}
        />
        {extra ?? <span />}
      </Grid>
      {caps?.volume !== false || caps.mute ? (
        <Flex align="center" gap={2}>
          <PlainButton
            aria-label={volume.muted ? 'Unmute' : 'Mute'}
            aria-pressed={volume.muted}
            title={volume.muted ? 'Unmute' : 'Mute'}
            disabled={!ready || caps?.mute === false}
            onClick={volume.toggleMute}
            center
            color={volume.muted ? 'accent' : 'textMuted'}
            css={{ flex: 'none' }}
          >
            <Icon name={volume.muted ? 'lu:volume-x' : 'lu:volume-2'} size={16} />
          </PlainButton>
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
          <Typography
            as="span"
            variant="secondary"
            color="textMuted"
            minWidth={32}
            align="right"
            css={{ flex: 'none', fontVariantNumeric: 'tabular-nums' }}
          >
            {volume.shown}%
          </Typography>
        </Flex>
      ) : null}
    </Flex>
  );
}

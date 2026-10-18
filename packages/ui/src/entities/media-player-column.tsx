import type { EntityRef } from '@hashsome/core';
import type { ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { HoldProgress } from '../layout/hold-progress.tsx';
import { IconButton } from '../layout/tile.tsx';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import { useHold } from '../layout/use-hold.ts';
import { MediaBrowser } from './media-browser.tsx';
import styles from './media-player-column.module.css';
import { MediaPlayerFull } from './media-player-full.tsx';
import { MediaQueue } from './media-queue.tsx';
import { SpeakersOverlay } from './media-speakers-pill.tsx';
import { NowPlaying } from './now-playing.tsx';

export interface MediaPlayerColumnProps {
  /** A media player, as a ref like `ma:kitchen` or `ha:media_player.kitchen`, or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'mediaPlayer'>;

  /** What to call the player: the drawer's title and the name shown when nothing is playing. Defaults to the player's own name. */
  name?: string;

  /** Content for the media browser shown with the player in the drawer. By default a `MediaBrowser` over the player's own library, shown only for a ref whose player has one; pass your own content to replace it, or `false` for no drawer. */
  browse?: ReactNode | false;

  /** The library and the queue each open as a full-size overlay of their own, from a library button and a queue button beside the transport, instead of one half-size drawer with the whole player in it: for a small display, where the drawer's half state is no use. The library overlay is a `MediaBrowser` as a list (or your `browse` content), the queue overlay a `MediaQueue`; each button is left out when the player has none, or for `browse={false}`. Holding the card and pressing the artwork then do nothing. Shuffle moves up beside the title. Default `false`. */
  overlays?: boolean;

  /** Whether the player can add speakers to what it plays, put it back to just itself and join a stream playing elsewhere (`MediaSpeakers`): a Speakers tab in the drawer's player, and with `overlays` a speakers button beside the title that opens it. Only for a player that can be grouped. `false` leaves it out. Default `true`. */
  grouping?: boolean;

  /** An allowlist of the speakers that can be added: only those that are in it and that the player can be grouped with are offered. Absent, every speaker it can be grouped with is offered. */
  speakers?: EntityRef[];
}

/** The player as an upright card for a narrow column beside a dashboard (a quarter to a third of
 * the width): the artwork in a ring that shows (and seeks) the position, title, transport and a
 * volume bar that is always visible. It is only as tall as it needs to be. Holding the card or
 * pressing the artwork opens the drawer with the player and its library; the browse button opens
 * that drawer expanded. With `overlays` there is no drawer: the library and the queue open full
 * size, each on its own. */
export function MediaPlayerColumn({
  entity,
  name,
  browse,
  overlays = false,
  grouping = true,
  speakers,
}: MediaPlayerColumnProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const player = handle.entity;
  const ref = typeof entity === 'string' ? entity : undefined;
  const browser =
    browse === false
      ? undefined
      : (browse ??
        (ref && player?.capabilities.browse ? (
          <MediaBrowser entity={ref} layout="auto" />
        ) : undefined));

  const fallback = fallbackName(entity);
  // Whether it can be grouped, for the pill under the track info that opens who plays together with it.
  const grouped = grouping && ref !== undefined && player?.capabilities.group === true;

  // The pill under the track info, which opens the speakers over the player.
  const pill =
    grouped && ref
      ? {
          speakers: (
            <SpeakersOverlay
              player={player}
              entity={ref}
              name={name}
              title={name ?? player?.name ?? fallback}
              allowed={speakers}
            />
          ),
        }
      : {};

  if (overlays) {
    // What the overlays hold: the library as a list, the queue; each only when the player has one.
    const title = name ?? player?.name ?? fallback;
    const libraryBody =
      browse === false
        ? undefined
        : (browse ??
          (ref && player?.capabilities.browse ? (
            <MediaBrowser entity={ref} layout="list" />
          ) : undefined));

    const queueBody =
      ref && player?.capabilities.queue === true ? <MediaQueue entity={ref} /> : undefined;

    return handle.status === 'ready' ? (
      <DrawerTrigger icon="lu:library" label="Library" kind={title} body={libraryBody ?? null}>
        {(_open, _expanded, openLibrary) => (
          <DrawerTrigger icon="lu:list-music" label="Queue" kind={title} body={queueBody ?? null}>
            {(_openToo, _expandedToo, openQueue) => (
              <Card>
                <NowPlaying
                  handle={handle}
                  fallback={fallback}
                  shuffle="title"
                  {...(name !== undefined ? { name } : {})}
                  {...(libraryBody
                    ? {
                        leading: (
                          <IconButton
                            icon="lu:library"
                            label="Browse media"
                            glyph={18}
                            onClick={openLibrary}
                          />
                        ),
                      }
                    : {})}
                  {...(queueBody
                    ? {
                        extra: (
                          <IconButton
                            icon="lu:list-music"
                            label="Queue"
                            glyph={18}
                            onClick={openQueue}
                          />
                        ),
                      }
                    : {})}
                  {...pill}
                />
              </Card>
            )}
          </DrawerTrigger>
        )}
      </DrawerTrigger>
    ) : (
      <Card>
        <NowPlaying
          handle={handle}
          fallback={fallback}
          {...(name !== undefined ? { name } : {})}
          {...pill}
        />
      </Card>
    );
  }

  return browser !== undefined && handle.status === 'ready' ? (
    <DrawerTrigger
      icon="lu:music"
      label={name ?? player?.name ?? fallback}
      kind="Media"
      body={
        <MediaPlayerFull
          entity={entity}
          browser={browser}
          {...(typeof entity === 'string' ? { queue: <MediaQueue entity={entity} /> } : {})}
          {...(speakers ? { speakers } : {})}
          grouping={grouping}
          speakersView="inline"
          {...(name !== undefined ? { name } : {})}
        />
      }
    >
      {(open, openExpanded) => (
        <Card open={open}>
          <NowPlaying
            handle={handle}
            fallback={fallback}
            {...(name !== undefined ? { name } : {})}
            onOpenArtwork={open}
            {...pill}
            extra={
              <IconButton
                icon="lu:library"
                label="Browse media"
                glyph={18}
                onClick={openExpanded}
              />
            }
          />
        </Card>
      )}
    </DrawerTrigger>
  ) : (
    <Card>
      <NowPlaying
        handle={handle}
        fallback={fallback}
        {...(name !== undefined ? { name } : {})}
        {...pill}
      />
    </Card>
  );
}

/** The card itself; with `open`, holding it (anywhere but on a control) calls it. */
function Card({ open, children }: { open?: () => void; children: ReactNode }) {
  const hold = useHold(() => open?.(), open !== undefined);
  return (
    <div data-grid-card className={styles.card} {...hold.handlers}>
      {children}
      {hold.holding ? <HoldProgress /> : null}
    </div>
  );
}

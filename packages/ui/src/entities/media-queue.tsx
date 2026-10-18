import { AudioLines, GripVertical, Music, Trash, X } from 'lucide-react';
import type { EntityRef, QueueItem } from '@hashsome/core';
import { useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react';
import { useEntityHandle } from '../hooks.ts';
import { Icon } from '../icon.tsx';
import { Cover } from '../layout/cover.tsx';
import { MarqueeText } from '../layout/marquee-text.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { useMediaQueue } from '../use-media-queue.ts';
import { useRows } from '../use-rows.ts';
import { formatDuration, formatLength } from './media-progress.ts';
import styles from './media-queue.module.css';
import { sourceLine } from './media-source.ts';

export interface MediaQueueProps {
  /** The media player, as a ref like `ma:living_room`. Shows nothing for a player with no queue. */
  entity: EntityRef;
}

/** The height of a track's row: a touch target, with room for its picture. */
const ROW = 56;

/**
 * A player's queue: the tracks that have just played (dimmed), the one playing, and what comes next,
 * as a list that scrolls. Tap a track to jump to it, the cross takes it out, and Clear takes out everything
 * after the track that is playing, which carries on. What comes next can be put in another order by
 * dragging a track by its handle (or with the arrow keys on the handle). Once the last track has
 * played and the player has stopped, that track is shown as played, not as playing. It is made to sit beside the player in a wide space (see `MediaPlayerFull`'s `queue`).
 */
export function MediaQueue({ entity }: MediaQueueProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const { queue, refresh } = useMediaQueue(entity, { duration: true });
  // Clearing a long queue takes a moment: the button waits, and what is being taken out is dimmed meanwhile.
  const [clearing, setClearing] = useState(false);
  // The order of what comes next while it is being changed, until the queue read afterwards replaces it.
  // It belongs to the queue it was made from: a queue read afterwards leaves it behind.
  const [draft, setDraft] = useState<{ of: unknown; ids: string[] } | null>(null);
  // A track being dragged by its handle (see `startDrag`).
  const [drag, setDrag] = useState<{
    id: string;
    from: number;
    target: number;
    offset: number;
  } | null>(null);

  // What comes next, in the order it is being put in, as far as it is drawn: a queue can be hundreds of tracks long,
  // and the rest come as the end of the list is scrolled to.
  const items = queue?.items ?? [];
  const at = items.findIndex((x) => x.current);
  const firstUpcoming = at === -1 ? items.length : at + 1;
  const leading = items.slice(0, firstUpcoming);
  const upcoming = items.slice(firstUpcoming);

  // A draft only counts while it is a reordering of the very tracks that are there.
  const byId = new Map(upcoming.map((item) => [item.id, item]));
  const shown =
    draft !== null &&
    draft.of === queue &&
    draft.ids.length === upcoming.length &&
    draft.ids.every((id) => byId.has(id))
      ? draft.ids.map((id) => byId.get(id)!)
      : upcoming;

  const { shown: visible, attach, onScroll, reserve } = useRows(shown, 'queue');
  if (!queue || handle.entity?.capabilities.queue !== true) {
    return null;
  }

  // After a change the queue is read again, once the player has had a moment to apply it.
  const run = (
    command: 'playQueueItem' | 'removeQueueItem' | 'clearQueue' | 'moveQueueItem',
    item?: QueueItem,
    shift?: number,
  ) => {
    const sent =
      command === 'clearQueue'
        ? handle.command('clearQueue')
        : command === 'moveQueueItem'
          ? handle.command('moveQueueItem', { item: item!.id, shift: shift! })
          : handle.command(command, { item: item!.id });

    if (command === 'clearQueue') {
      setClearing(true);
    }

    void sent
      .then(() => setTimeout(refresh, 400))
      .catch(() => setDraft(null))
      .finally(() => setClearing(false));
  };

  // The player has stopped on the last track and its position is at the end: it is over. A track
  // that was merely paused or stopped part way through is not, whatever state the player reports.
  const { playback, position, duration } = handle.entity;
  const stopped =
    (playback === 'idle' || playback === 'off') &&
    position !== undefined &&
    duration !== undefined &&
    position >= duration - 2;

  const finished =
    stopped &&
    at !== -1 &&
    at === queue.items.length - 1 &&
    queue.offset + queue.items.length === queue.total;

  // Dropping a track puts it where it was dragged to: the command says by how many places it moved.
  const moveTo = (item: QueueItem, from: number, to: number) => {
    if (to === from) {
      setDraft(null);
      return;
    }

    const ids = visible.map((x) => x.id);
    ids.splice(to, 0, ids.splice(from, 1)[0]!);
    setDraft({ of: queue, ids: [...ids, ...shown.slice(visible.length).map((x) => x.id)] });
    run('moveQueueItem', item, to - from);
  };

  // Dragging a row by its handle: it follows the finger, the rows it passes over move aside, and where it is let go is where it
  // goes. Every row is one height, so the place it is over is arithmetic, with nothing measured but the distance between two rows.
  const startDrag = (item: QueueItem, from: number, event: PointerEvent<HTMLElement>) => {
    const list = event.currentTarget.closest('ul');
    const rows = list?.querySelectorAll<HTMLElement>('[data-reorderable="true"]');
    const measured = rows && rows[1] && rows[0] ? rows[1].offsetTop - rows[0].offsetTop : 0;
    const pitch = measured > 0 ? measured : ROW;
    const startY = event.clientY;
    const last = visible.length - 1;
    const place = (y: number) => {
      const offset = y - startY;
      const target = Math.min(last, Math.max(0, Math.round(from + offset / pitch)));
      setDrag({ id: item.id, from, target, offset });
      return target;
    };

    const move = (e: globalThis.PointerEvent) => place(e.clientY);
    const stop = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      window.removeEventListener('pointercancel', cancel);
    };

    const up = (e: globalThis.PointerEvent) => {
      stop();
      const target = place(e.clientY);
      setDrag(null);
      moveTo(item, from, target);
    };

    const cancel = () => {
      stop();
      setDrag(null);
    };

    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    window.addEventListener('pointercancel', cancel);
    setDrag({ id: item.id, from, target: from, offset: 0 });
  };

  // The same from the keyboard: one place up or down, straight away.
  const step = (item: QueueItem, by: -1 | 1) => {
    const from = shown.findIndex((x) => x.id === item.id);
    const to = from + by;
    if (to < 0 || to >= shown.length) {
      return;
    }

    const ids = shown.map((x) => x.id);
    ids.splice(to, 0, ids.splice(from, 1)[0]!);
    setDraft({ of: queue, ids });
    run('moveQueueItem', item, by);
  };

  const source = handle.entity?.source;
  const hidden = Math.max(0, queue.total - queue.offset - queue.items.length);
  return (
    <div
      className={styles.queue}
      style={{ '--row': `${ROW}px`, '--grip': `${GRIP}px` } as CSSProperties}
    >
      <div className={styles.head}>
        <div className={styles.titles}>
          <h2 className={styles.heading}>
            Queue
            <span className={styles.count}>
              {' '}
              · {queue.total} {queue.total === 1 ? 'track' : 'tracks'}
              {queue.duration !== undefined && queue.total > 0
                ? ` · ${formatLength(queue.duration)}`
                : ''}
            </span>
          </h2>
          {source ? (
            <span className={styles.source} title={sourceLine(source)}>
              {sourceLine(source)}
            </span>
          ) : null}
        </div>
        {queue.total > 0 ? (
          <PlainButton
            className={styles.clear}
            aria-label="Clear the queue"
            title="Clear what comes next; the track playing carries on"
            disabled={clearing}
            onClick={() => run('clearQueue')}
          >
            <Icon size={14}>
              <Trash />
            </Icon>
            <span className={styles.clearLabel}>Clear</span>
          </PlainButton>
        ) : null}
      </div>

      {queue.items.length === 0 ? (
        <p className={styles.empty}>Nothing is queued. Play or add something from the library.</p>
      ) : (
        <ul className={styles.list} ref={attach} onScroll={onScroll}>
          {leading.map((item, index) => (
            <QueueRow
              key={item.id}
              item={item}
              dim={index < at || (finished && item.current === true)}
              played={finished && item.current === true}
              onPlay={() => run('playQueueItem', item)}
            />
          ))}
          {visible.map((item, index) => (
            <QueueRow
              key={item.id}
              item={item}
              // While Clear works only what is on its way out is dimmed.
              dim={clearing}
              reorderable
              dragging={drag?.id === item.id ? drag.offset : undefined}
              // The rows the dragged one passes over move one place aside.
              shift={
                drag && drag.id !== item.id
                  ? drag.from < drag.target && index > drag.from && index <= drag.target
                    ? -1
                    : drag.target < drag.from && index >= drag.target && index < drag.from
                      ? 1
                      : 0
                  : 0
              }
              onPlay={() => run('playQueueItem', item)}
              onRemove={() => run('removeQueueItem', item)}
              onGrab={(event) => startDrag(item, index, event)}
              onStep={(by) => step(item, by)}
            />
          ))}
          <li {...reserve} />
          {hidden > 0 ? <li className={styles.more}>and {hidden.toLocaleString()} more</li> : null}
        </ul>
      )}
    </div>
  );
}

interface QueueRowProps {
  item: QueueItem;

  /** Shown faded: already played, or on its way out. */
  dim: boolean;

  /** The track that was playing, now over: it is not shown as playing. */
  played?: boolean;
  reorderable?: boolean;

  /** Set while this row is being dragged: how far, in px, it is from where it was. */
  dragging?: number | undefined;

  /** A row another one is being dragged past gives way: one place up (-1) or down (1). */
  shift?: -1 | 0 | 1;
  onPlay: () => void;
  onRemove?: () => void;
  onGrab?: (event: PointerEvent<HTMLElement>) => void;
  onStep?: (by: -1 | 1) => void;
}

/** Room for the grip that a track in what comes next has, so every row lines up. */
const GRIP = 20;

function QueueRow({
  item,
  dim,
  played = false,
  reorderable = false,
  dragging,
  shift = 0,
  onPlay,
  onRemove,
  onGrab,
  onStep,
}: QueueRowProps) {
  const playing = item.current === true && !played;
  return (
    <li
      className={styles.row}
      data-reorderable={reorderable}
      data-dragging={dragging !== undefined}
      data-dim={dim}
      style={
        {
          '--shift': shift,
          ...(dragging === undefined ? {} : { '--drag': `${dragging}px` }),
        } as CSSProperties
      }
    >
      {reorderable ? (
        <PlainButton
          className={styles.grip}
          aria-label={`Move ${item.title}`}
          title="Drag to move, or use the arrow keys"
          onPointerDown={onGrab}
          onKeyDown={(event: KeyboardEvent) => {
            if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              event.preventDefault();
              onStep?.(event.key === 'ArrowUp' ? -1 : 1);
            }
          }}
        >
          <Icon size={16}>
            <GripVertical />
          </Icon>
        </PlainButton>
      ) : (
        <span className={styles.spacer} />
      )}
      <PlainButton
        className={styles.track}
        aria-label={`${playing ? 'Playing' : 'Play'} ${item.title}`}
        aria-current={playing ? 'true' : undefined}
        onClick={onPlay}
      >
        <span className={styles.art}>
          {item.artworkUrl ? (
            <Cover src={item.artworkUrl} size="small" />
          ) : (
            <Icon size={18}>{playing ? <AudioLines /> : <Music />}</Icon>
          )}
        </span>
        <span className={styles.names}>
          <span className={styles.title}>
            <MarqueeText>{item.title}</MarqueeText>
          </span>
          {item.artist ? (
            <span className={styles.artist}>
              <MarqueeText>{item.artist}</MarqueeText>
            </span>
          ) : null}
        </span>
        {item.duration !== undefined ? (
          <span className={styles.time}>{formatDuration(item.duration)}</span>
        ) : null}
      </PlainButton>
      {onRemove ? (
        <PlainButton
          className={styles.remove}
          aria-label={`Remove ${item.title} from the queue`}
          title="Remove from the queue"
          onClick={onRemove}
        >
          <Icon size={16}>
            <X />
          </Icon>
        </PlainButton>
      ) : (
        <span className={styles.spacerEnd} />
      )}
    </li>
  );
}

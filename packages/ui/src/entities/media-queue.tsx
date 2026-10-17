import type { EntityRef, QueueItem } from '@hashsome/core';
import { AnimatePresence, motion, Reorder, useDragControls } from 'motion/react';
import { useState, type CSSProperties, type KeyboardEvent } from 'react';
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
import { useStill } from '../layout/use-still.ts';

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
  const still = useStill();
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

  const { shown: visible, attach, onScroll } = useRows(shown, 'queue');
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

  // Dropping a track puts it where the draft has it: the command says by how many places it moved.
  const drop = (item: QueueItem) => {
    const to = shown.findIndex((x) => x.id === item.id);
    const from = upcoming.findIndex((x) => x.id === item.id);
    if (to === from) {
      setDraft(null);
      return;
    }

    run('moveQueueItem', item, to - from);
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
            <Icon name="lu:trash" size={14} />
            <span className={styles.clearLabel}>Clear</span>
          </PlainButton>
        ) : null}
      </div>

      {queue.items.length === 0 ? (
        <p className={styles.empty}>Nothing is queued. Play or add something from the library.</p>
      ) : (
        <Reorder.Group
          as="ul"
          axis="y"
          ref={attach}
          onScroll={onScroll}
          values={visible.map((item) => item.id)}
          // What is drawn is put in its new order; what is not drawn yet stays after it.
          onReorder={(ids) =>
            setDraft({
              of: queue,
              ids: [...ids, ...shown.slice(visible.length).map((x) => x.id)],
            })
          }
          className={styles.list}
        >
          {/* A track that arrives grows in, one that leaves closes up, and the rest slide to fill the
              space, so removing one, jumping to one or clearing the queue is something to watch rather
              than a list that changes in one frame. The ones there from the start do not animate. */}
          <AnimatePresence initial={false}>
            {leading.map((item, index) => (
              <QueueRow
                key={item.id}
                item={item}
                still={still === true}
                dim={index < at || (finished && item.current === true)}
                played={finished && item.current === true}
                onPlay={() => run('playQueueItem', item)}
              />
            ))}
            {visible.map((item) => (
              <QueueRow
                key={item.id}
                item={item}
                still={still === true}
                // While Clear works only what is on its way out is dimmed.
                dim={clearing}
                reorderable
                onPlay={() => run('playQueueItem', item)}
                onRemove={() => run('removeQueueItem', item)}
                onDrop={() => drop(item)}
                onStep={(by) => step(item, by)}
              />
            ))}
          </AnimatePresence>
          {hidden > 0 ? <li className={styles.more}>and {hidden.toLocaleString()} more</li> : null}
        </Reorder.Group>
      )}
    </div>
  );
}

interface QueueRowProps {
  item: QueueItem;
  still: boolean;

  /** Shown faded: already played, or on its way out. */
  dim: boolean;

  /** The track that was playing, now over: it is not shown as playing. */
  played?: boolean;
  reorderable?: boolean;
  onPlay: () => void;
  onRemove?: () => void;
  onDrop?: () => void;
  onStep?: (by: -1 | 1) => void;
}

/** Room for the grip that a track in what comes next has, so every row lines up. */
const GRIP = 20;

function QueueRow({
  item,
  still,
  dim,
  played = false,
  reorderable = false,
  onPlay,
  onRemove,
  onDrop,
  onStep,
}: QueueRowProps) {
  const controls = useDragControls();
  const [dragging, setDragging] = useState(false);
  const playing = item.current === true && !played;
  const shared = {
    initial: { opacity: 0, height: 0 },
    animate: { opacity: dim ? 0.5 : 1, height: ROW },
    exit: { opacity: 0, height: 0 },
    transition: { duration: still ? 0 : 0.2, ease: 'easeOut' as const },
  };

  const content = (
    <>
      {reorderable ? (
        <PlainButton
          className={styles.grip}
          aria-label={`Move ${item.title}`}
          title="Drag to move, or use the arrow keys"
          onPointerDown={(event: React.PointerEvent) => controls.start(event)}
          onKeyDown={(event: KeyboardEvent) => {
            if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
              event.preventDefault();
              onStep?.(event.key === 'ArrowUp' ? -1 : 1);
            }
          }}
        >
          <Icon name="lu:grip-vertical" size={16} />
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
            <Icon name={playing ? 'lu:audio-lines' : 'lu:music'} size={18} />
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
          <Icon name="lu:x" size={16} />
        </PlainButton>
      ) : (
        <span className={styles.spacerEnd} />
      )}
    </>
  );

  return reorderable ? (
    <Reorder.Item
      value={item.id}
      dragListener={false}
      dragControls={controls}
      onDragStart={() => setDragging(true)}
      onDragEnd={() => {
        setDragging(false);
        onDrop?.();
      }}
      {...shared}
      className={styles.row}
      data-dragging={dragging}
    >
      {content}
    </Reorder.Item>
  ) : still ? (
    <li className={styles.row}>{content}</li>
  ) : (
    <motion.li {...shared} className={styles.row}>
      {content}
    </motion.li>
  );
}

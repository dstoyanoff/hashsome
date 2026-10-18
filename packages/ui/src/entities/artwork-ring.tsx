import { Music } from 'lucide-react';
import { useRef, type CSSProperties, type KeyboardEvent, type PointerEvent } from 'react';
import { Icon } from '../icon.tsx';
import { Cover } from '../layout/cover.tsx';
import styles from './artwork-ring.module.css';
import type { SeekHold } from './media-seek.ts';

/** The ring's diameter when none is given. */
const DEFAULT_SIZE = 168;

/** The player's artwork in a circle, like the one in the bar but larger: an inner outline around
 * it, and an outer ring that shows how far along playback is. With `onSeek` the ring is a slider:
 * drag around it, or use the arrow keys. Not exported from the package: `MediaPlayerColumn` and
 * `MediaPlayerFull` draw it. */
export function ArtworkRing({
  artworkUrl,
  seek,
  duration,
  seekable,
  onOpen,
  size: SIZE = DEFAULT_SIZE,
  maxSize = Infinity,
}: {
  artworkUrl?: string | undefined;

  /** The position to draw and the way to change it, shared with whatever shows the time. */
  seek: SeekHold;

  /** Seconds. Without it the ring is only an outline. */
  duration?: number | undefined;
  seekable: boolean;

  /** Makes the artwork itself a button, for opening something (the library). */
  onOpen?: (() => void) | undefined;

  /** The ring's diameter in px, when there is room for it; it shrinks in a column that is shorter. Default 168. */
  size?: number;

  /** The most it grows to, in px, when the column has more room than `size` (a card stretched beside other cards). Default: as far as the width allows. */
  maxSize?: number;
}) {
  const ring = useRef<HTMLDivElement>(null);

  /** A drag in progress: where round the ring the pointer was last, and how far along it has taken
   * the position, which may run past either end while the pointer carries on (see `follow`). */
  const drag = useRef<{ turn: number; along: number } | null>(null);
  const total = seekable && duration !== undefined && duration > 0 ? duration : undefined;
  const shown = seek.shown;
  const fraction =
    shown !== undefined && duration ? Math.min(1, Math.max(0, shown / duration)) : undefined;

  /** How far round the ring a pointer is, from the top, clockwise: 0 to 1. */
  const turnAt = (event: PointerEvent<HTMLDivElement>): number | undefined => {
    const box = ring.current?.getBoundingClientRect();
    if (!box) {
      return undefined;
    }

    const dx = event.clientX - (box.left + box.width / 2);
    const dy = event.clientY - (box.top + box.height / 2);
    return (Math.atan2(dx, -dy) / (2 * Math.PI) + 1) % 1;
  };

  /** Moves a drag with the pointer and says where in the item that is. The position follows how far the
   * pointer has gone round, not which side of the ring it is on, and stops at the start and the end:
   * carrying on past the top (the start and the end meet there) holds it where it is, instead of
   * jumping to the other end, and it only moves again once the pointer is back round. */
  const follow = (event: PointerEvent<HTMLDivElement>): number | undefined => {
    const now = turnAt(event);
    const held = drag.current;
    if (now === undefined || !held || !duration) {
      return undefined;
    }

    // The way round that is the short one, so crossing the top does not read as a whole turn.
    let step = now - held.turn;
    if (step > 0.5) {
      step -= 1;
    } else if (step < -0.5) {
      step += 1;
    }

    // Only a little past either end is remembered, so the pointer is never far from its place.
    held.along = Math.min(1.25, Math.max(-0.25, held.along + step));
    held.turn = now;
    return Math.round(Math.min(1, Math.max(0, held.along)) * duration);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = event.key === 'ArrowRight' || event.key === 'ArrowUp' ? 5 : -5;
    if (
      total === undefined ||
      !['ArrowRight', 'ArrowUp', 'ArrowLeft', 'ArrowDown'].includes(event.key)
    ) {
      return;
    }

    event.preventDefault();
    seek.commit(Math.min(total, Math.max(0, Math.round((shown ?? 0) + step))));
  };

  return (
    <div
      className={styles.room}
      style={
        { '--size': `${SIZE}px`, '--max-size': `${Math.max(maxSize, SIZE)}px` } as CSSProperties
      }
    >
      <div className={styles.ring}>
        <div
          ref={ring}
          className={styles.track}
          {...(total !== undefined
            ? {
                role: 'slider',
                tabIndex: 0,
                'aria-label': 'Position',
                'aria-valuemin': 0,
                'aria-valuemax': Math.round(total ?? 0),
                'aria-valuenow': Math.round(shown ?? 0),
                onKeyDown,
                onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
                  const box = event.currentTarget.getBoundingClientRect();
                  const distance = Math.hypot(
                    event.clientX - (box.left + box.width / 2),
                    event.clientY - (box.top + box.height / 2),
                  );

                  // Only the ring itself seeks, not the artwork inside it.
                  if (distance < box.width * 0.4) {
                    return;
                  }

                  // Where it was pressed is where the position starts.
                  const turn = turnAt(event) ?? 0;
                  drag.current = { turn, along: turn };
                  event.currentTarget.setPointerCapture?.(event.pointerId);
                  seek.preview(Math.round(turn * total));
                },
                onPointerMove: (event: PointerEvent<HTMLDivElement>) => {
                  if (drag.current) {
                    seek.preview(follow(event) ?? 0);
                  }
                },
                onPointerUp: (event: PointerEvent<HTMLDivElement>) => {
                  if (drag.current) {
                    const next = follow(event);
                    drag.current = null;
                    if (next !== undefined) {
                      seek.commit(next);
                    }
                  }
                },
                // A touch the browser took or lost never ends with a release: let go of the position.
                onPointerCancel: () => {
                  drag.current = null;
                  seek.cancel();
                },
                onLostPointerCapture: () => {
                  if (drag.current) {
                    drag.current = null;
                    seek.cancel();
                  }
                },
              }
            : {})}
        >
          <svg viewBox="0 0 100 100" width="100%" height="100%" aria-hidden="true">
            <circle className={styles.base} cx="50" cy="50" r="47" fill="none" strokeWidth="3" />
            {fraction !== undefined ? (
              <circle
                className={styles.progress}
                cx="50"
                cy="50"
                r="47"
                fill="none"
                strokeWidth="3"
                strokeLinecap="round"
                pathLength={100}
                strokeDasharray={`${fraction * 100} 100`}
                transform="rotate(-90 50 50)"
              />
            ) : null}
          </svg>
        </div>
        <div className={styles.outline} />
        <div
          className={styles.art}
          {...(onOpen
            ? {
                role: 'button',
                tabIndex: 0,
                'aria-label': 'Open media browser',
                title: 'Open media browser',
                onClick: onOpen,
                onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onOpen();
                  }
                },
              }
            : {})}
        >
          {artworkUrl ? (
            <Cover src={artworkUrl} size="medium" />
          ) : (
            <Icon size={40}>
              <Music />
            </Icon>
          )}
        </div>
      </div>
    </div>
  );
}

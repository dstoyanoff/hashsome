/** @jsxImportSource @emotion/react */
import { Box } from '../style/prim.tsx';
import { useRef, type KeyboardEvent, type PointerEvent } from 'react';
import type { SeekHold } from './media-seek.ts';

/** The thin progress line along the bottom of the media bar. A tall invisible strip around it is
 * the touch target; with `seekable` a press or drag on it seeks, and the arrow keys nudge it. */
export function SeekLine({
  seek,
  duration,
  seekable,
}: {
  seek: SeekHold;

  /** Seconds. */
  duration: number;
  seekable: boolean;
}) {
  const dragging = useRef(false);
  const shown = Math.min(duration, Math.max(0, seek.shown ?? 0));
  const fraction = duration > 0 ? shown / duration : 0;

  const positionAt = (event: PointerEvent<HTMLDivElement>): number => {
    const box = event.currentTarget.getBoundingClientRect();
    const along = box.width > 0 ? (event.clientX - box.left) / box.width : 0;
    return Math.round(Math.min(1, Math.max(0, along)) * duration);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return;
    }

    event.preventDefault();
    const step = event.key === 'ArrowRight' ? 5 : -5;
    seek.commit(Math.min(duration, Math.max(0, Math.round(shown + step))));
  };

  return (
    <Box
      position="absolute"
      height={16}
      cursor={seekable ? 'pointer' : 'default'}
      {...(seekable
        ? {
            role: 'slider',
            tabIndex: 0,
            onKeyDown,
            onPointerDown: (event: PointerEvent<HTMLDivElement>) => {
              dragging.current = true;
              event.currentTarget.setPointerCapture?.(event.pointerId);
              seek.preview(positionAt(event));
            },
            onPointerMove: (event: PointerEvent<HTMLDivElement>) => {
              if (dragging.current) {
                seek.preview(positionAt(event));
              }
            },
            onPointerUp: (event: PointerEvent<HTMLDivElement>) => {
              if (dragging.current) {
                dragging.current = false;
                seek.commit(positionAt(event));
              }
            },
            // A touch the browser took (a gesture, a scroll) or lost never ends with a release: give
            // the position back to the player instead of leaving it where the finger was.
            onPointerCancel: () => {
              dragging.current = false;
              seek.cancel();
            },
            onLostPointerCapture: () => {
              if (dragging.current) {
                dragging.current = false;
                seek.cancel();
              }
            },
          }
        : { role: 'progressbar' })}
      aria-label="Position"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(shown)}
      css={({ palette }) => ({
        left: 0,
        right: 0,
        bottom: 0,
        touchAction: 'none',
        '&::before, &::after': {
          content: '""',
          position: 'absolute',
          left: 0,
          bottom: 0,
          height: 3,
          transition: 'height 0.12s ease',
        },
        // The track only shows when there is something to grab.
        '&::before': { right: 0, background: seekable ? palette.border : 'transparent' },
        '&::after': { width: `${fraction * 100}%`, background: palette.accent },
        ...(seekable
          ? {
              '&:hover::before, &:hover::after, &:focus-visible::before, &:focus-visible::after': {
                height: 6,
              },
              outline: 'none',
            }
          : {}),
      })}
    />
  );
}

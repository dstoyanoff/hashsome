import type { EntityRef, QueueResult } from '@hashsome/core';
import { useCallback, useEffect, useState } from 'react';
import { useEntity, useReconnects } from './hooks.ts';
import { useMediaPending } from './entities/media-pending.ts';
import { useClient } from './provider.tsx';

export interface MediaQueueState {
  /** `undefined` until the first answer, and always for a player with no queue. */
  queue: QueueResult | undefined;
  loading: boolean;

  /** Reads the queue again, e.g. after something was added to it or taken out. */
  refresh(): void;
}

/** How often the queue is read again while it is on screen, to pick up what another app changed. */
const POLL_MS = 15_000;

/**
 * A player's queue, read when `ref` changes and again whenever the track that plays changes (the
 * window of the queue moves with it), after `refresh()`, and every so often. A player with no queue,
 * or an error, leaves `queue` undefined: the queue is an extra, so a component just shows nothing.
 * Pass `undefined` to not ask at all.
 */
export function useMediaQueue(ref: EntityRef | undefined): MediaQueueState {
  const client = useClient();
  const player = useEntity(ref);
  const [state, setState] = useState<{ ref: string; queue: QueueResult | undefined }>();
  const [tick, setTick] = useState(0);
  const reconnects = useReconnects();
  const refresh = useCallback(() => setTick((now) => now + 1), []);

  // What changes when the queue's window does: the track playing and the order it plays in.
  const track = player?.kind === 'mediaPlayer' ? player.media?.title : undefined;
  const shuffle = player?.kind === 'mediaPlayer' ? player.shuffle : undefined;
  // Something added to or played from the library changes the queue without changing the track.
  const asked = useMediaPending(ref).length;
  const has = player?.kind === 'mediaPlayer' && player.capabilities.queue;

  useEffect(() => {
    if (!ref || !has) {
      return;
    }

    let current = true;
    const read = () =>
      client.queue(ref, {}).then(
        (queue) => current && setState({ ref, queue }),
        () => current && setState({ ref, queue: undefined }),
      );

    void read();
    const timer = setInterval(() => void read(), POLL_MS);
    return () => {
      current = false;
      clearInterval(timer);
    };
    // `track`, `shuffle`, `tick`, `reconnects` and `asked` are not read inside: a change to any of them is the reason to read again.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [client, ref, has, track, shuffle, tick, reconnects, asked]);

  const answered = state !== undefined && state.ref === ref;
  return {
    queue: answered ? state.queue : undefined,
    loading: ref !== undefined && has === true && !answered,
    refresh,
  };
}

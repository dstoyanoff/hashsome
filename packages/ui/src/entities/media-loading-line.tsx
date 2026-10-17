import type { EntityRef } from '@hashsome/core';
import { Spinner } from '../layout/spinner.tsx';
import { useMediaPending } from './media-pending.ts';
import styles from './media-loading-line.module.css';

/** What a player has been asked to start and is not ready with yet, as the line a player shows in the place
 * of the artist: a spinner and "Loading <title>…". Nothing, when it has been asked nothing. Starting a
 * playlist can take many seconds, during which the player would otherwise look as if nothing was happening. */
export function useLoadingLabel(ref: EntityRef | undefined): string | undefined {
  return useMediaPending(ref).find((entry) => entry.kind === 'playItem')?.label;
}

export function LoadingLine({ label, variant }: { label: string; variant: 'body' | 'secondary' }) {
  return (
    <div className={styles.line}>
      <Spinner size={variant === 'body' ? 14 : 12} label="Loading" />
      <span className={styles[variant]}>Loading {label}…</span>
    </div>
  );
}

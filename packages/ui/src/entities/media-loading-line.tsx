/** @jsxImportSource @emotion/react */
import { Flex, Typography } from '../style/prim.tsx';
import type { EntityRef } from '@hashsome/core';
import { Spinner } from '../layout/spinner.tsx';
import { useMediaPending } from './media-pending.ts';

/** What a player has been asked to start and is not ready with yet, as the line a player shows in the place
 * of the artist: a spinner and "Loading <title>…". Nothing, when it has been asked nothing. Starting a
 * playlist can take many seconds, during which the player would otherwise look as if nothing was happening. */
export function useLoadingLabel(ref: EntityRef | undefined): string | undefined {
  return useMediaPending(ref).find((entry) => entry.kind === 'playItem')?.label;
}

export function LoadingLine({ label, variant }: { label: string; variant: 'body' | 'secondary' }) {
  return (
    <Flex align="center" gap={1.5} minWidth={0} color="accent" css={{ minWidth: 0 }}>
      <Spinner size={variant === 'body' ? 14 : 12} label="Loading" />
      <Typography as="span" variant={variant} noWrap textOverflow="ellipsis" overflow="hidden">
        Loading {label}…
      </Typography>
    </Flex>
  );
}

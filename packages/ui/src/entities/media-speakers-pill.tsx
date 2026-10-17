import type { EntityRef, MediaPlayerEntity } from '@hashsome/core';
import { memo } from 'react';
import { useEntities } from '../hooks.ts';
import { Icon } from '../icon.tsx';
import { IconButton } from '../layout/tile.tsx';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import styles from './media-speakers-pill.module.css';
import { MediaSpeakers, offeredSpeakers } from './media-speakers.tsx';

/** What the pill says: how many speakers play together, or which stream is playing in another room, or nothing. */
export interface SpeakersSummary {
  /** The speakers playing together with this player, itself included; 0 while it plays alone. */
  together: number;

  /** The streams playing on the speakers it could join, named by the player that leads each. Empty while it is in a group. */
  elsewhere: string[];
}

/** The speakers a player could be grouped with, narrowed to an allowlist when there is one, and what they play. */
export function useSpeakersSummary(
  player: MediaPlayerEntity | undefined,
  allowed: readonly EntityRef[] | undefined,
): SpeakersSummary {
  const offered = offeredSpeakers(player?.groupable, allowed).filter((ref) => ref !== player?.ref);
  const states = useEntities(offered);
  const group = player?.group;
  if (group) {
    return { together: group.members.length + 1, elsewhere: [] };
  }

  // One stream per leader: two speakers playing together are one stream, named by who leads it.
  const byRef = new Map<string, string>();
  states.forEach((state, index) => {
    const ref = offered[index];
    if (ref !== undefined && state?.kind === 'mediaPlayer') {
      byRef.set(ref, state.name);
    }
  });

  const streams = new Map<string, string>();
  states.forEach((state) => {
    if (state?.kind !== 'mediaPlayer' || state.availability !== 'ready') {
      return;
    }

    if (state.playback !== 'playing') {
      return;
    }

    const leader = state.group?.leader ?? state.ref;
    if (!streams.has(leader)) {
      streams.set(leader, byRef.get(leader) ?? state.name);
    }
  });

  return { together: 0, elsewhere: [...streams.values()] };
}

/** The words for a summary: "Speakers" with nothing going on, "3 speakers" in a group, "Patio playing" or "2 streams playing" when there is a stream to join. */
export function summaryLabel(summary: SpeakersSummary): string {
  if (summary.together > 1) {
    return `${summary.together} speakers`;
  }

  const [only] = summary.elsewhere;
  if (summary.elsewhere.length === 1 && only !== undefined) {
    return `${only} playing`;
  }

  if (summary.elsewhere.length > 1) {
    return `${summary.elsewhere.length} streams playing`;
  }

  return 'Speakers';
}

/**
 * A small pill that says how a player's speakers stand and opens them: "Speakers" while it plays alone, "3 speakers"
 * once others play with it, "Patio playing" when there is a stream in another room to join. A dot marks a stream to
 * join, and the speaker icon takes the accent colour while the player is in a group.
 */
export function SpeakersPill({
  player,
  speakers,
  onClick,
  pressed,
  compact = false,
}: {
  /** The player whose speakers they are. */
  player: MediaPlayerEntity | undefined;

  /** An allowlist of the speakers to count, as `MediaSpeakers` takes it. */
  speakers?: readonly EntityRef[] | undefined;
  onClick: () => void;

  /** Whether what it opens is open, for a pill that toggles an inline view. */
  pressed?: boolean | undefined;

  /** Without words, for a bar with no room: the icon, and the count in a group. */
  compact?: boolean;
}) {
  const summary = useSpeakersSummary(player, speakers);
  const label = summaryLabel(summary);
  const grouped = summary.together > 1;
  const offering = !grouped && summary.elsewhere.length > 0;
  const words = compact ? (grouped ? String(summary.together) : undefined) : label;

  return (
    <button
      type="button"
      className={styles.pill}
      data-grouped={grouped}
      data-bare={words === undefined}
      aria-label={`Speakers: ${label}`}
      aria-haspopup="dialog"
      aria-expanded={pressed}
      title={label}
      onClick={onClick}
    >
      <span className={styles.icon}>
        <Icon name="lu:speaker" size={14} />
      </span>
      {offering ? <span className={styles.dot} aria-hidden="true" /> : null}
      {words !== undefined ? <span className={styles.words}>{words}</span> : null}
    </button>
  );
}

/** The pill, opening the speakers in the side panel every media overlay uses, which can be made full size. It builds
 * the speakers itself and is memoized: the panel keeps its content live by pushing it again whenever it is a new
 * element, and a parent that re-renders when the panel opens (as the full player does) would otherwise build a new one
 * each time, and push it again, without end. */
export const SpeakersOverlay = memo(function SpeakersOverlay({
  player,
  entity,
  name,
  title,
  allowed,
  compact,
}: {
  player: MediaPlayerEntity | undefined;

  /** The player, as a ref. */
  entity: EntityRef;

  /** What to call it, when it is not the name it reports. */
  name?: string | undefined;

  /** The player's name, under the panel's heading. */
  title: string;
  allowed?: readonly EntityRef[] | undefined;
  compact?: boolean;
}) {
  const body = (
    <MediaSpeakers
      entity={entity}
      {...(name !== undefined ? { name } : {})}
      {...(allowed ? { speakers: [...allowed] } : {})}
    />
  );

  return (
    <DrawerTrigger icon="lu:speaker" label="Speakers" kind={title} body={body}>
      {(open) => (
        <SpeakersPill
          player={player}
          speakers={allowed}
          onClick={open}
          {...(compact ? { compact } : {})}
        />
      )}
    </DrawerTrigger>
  );
});

/** The speakers as a round icon button, the size of the transport's, for a player with no room for the pill: it takes
 * the accent colour while the player plays in a group, and opens the speakers in the same side panel as the pill.
 * Memoized for the same reason `SpeakersOverlay` is. */
export const SpeakersButton = memo(function SpeakersButton({
  player,
  entity,
  name,
  title,
  allowed,
}: {
  player: MediaPlayerEntity | undefined;

  /** The player, as a ref. */
  entity: EntityRef;

  /** What to call it, when it is not the name it reports. */
  name?: string | undefined;

  /** The player's name, under the panel's heading. */
  title: string;
  allowed?: readonly EntityRef[] | undefined;
}) {
  const summary = useSpeakersSummary(player, allowed);
  const body = (
    <MediaSpeakers
      entity={entity}
      {...(name !== undefined ? { name } : {})}
      {...(allowed ? { speakers: [...allowed] } : {})}
    />
  );

  return (
    <DrawerTrigger icon="lu:speaker" label="Speakers" kind={title} body={body}>
      {(open) => (
        <IconButton
          icon="lu:speaker"
          label={`Speakers: ${summaryLabel(summary)}`}
          glyph={18}
          active={summary.together > 1}
          onClick={open}
        />
      )}
    </DrawerTrigger>
  );
});

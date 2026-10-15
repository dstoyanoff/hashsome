/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import { Flex, Typography } from 'e-prim';
import { useState, type ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntities, useEntityHandle } from '../hooks.ts';
import { Icon } from '../icon.tsx';
import type { IconName } from '../icon-data.ts';
import { ConfirmDialog } from '../layout/confirm-dialog.tsx';
import { Cover } from '../layout/cover.tsx';
import { ValueBar } from '../layout/drawer-controls.tsx';
import { Skeleton } from '../layout/skeleton.tsx';
import { Spinner } from '../layout/spinner.tsx';
import { useClient } from '../provider.tsx';
import { statusLabels } from '../status.ts';
import { usePressFeedback, useVolumeControl } from './media-controls.ts';

export interface MediaSpeakersProps {
  /** The player whose stream this is, as a ref like `ma:porch` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'mediaPlayer'>;

  /** What to call the player. Defaults to the player's own name. */
  name?: string;

  /** An allowlist of the speakers that can be added to this stream (or joined). Only those that are in it and that the player can be grouped with are offered; a speaker the player cannot be grouped with is left out, and one that is but is unavailable shows dimmed. Absent, every speaker it can be grouped with is offered. Speakers already in the stream are always shown, so they can be taken out. */
  speakers?: EntityRef[];
}

/** The speakers to offer: the ones the player can be grouped with, narrowed to an allowlist when there is one. */
export function offeredSpeakers(
  groupable: readonly EntityRef[] | undefined,
  allowed: readonly EntityRef[] | undefined,
): EntityRef[] {
  const can = groupable ?? [];
  return allowed ? can.filter((ref) => allowed.includes(ref)) : [...can];
}

/** What a row says about its player. */
interface Speaker {
  ref: EntityRef;
  name: string;
  available: boolean;

  /** The title it is playing, while it plays. */
  playing: string | undefined;

  /** The player whose stream it is in, when it is in a group. */
  leader: EntityRef | undefined;
}

/**
 * The speakers of a player's stream: who plays together with it, a volume for each, a way to take one out, to add
 * another, to join a stream that is playing in another room, and to put the stream back to just this player.
 * Everything is asked of the backend and shown when it answers. Playing something else on a speaker, or on this
 * player, to do it asks first.
 */
export function MediaSpeakers({ entity, name, speakers }: MediaSpeakersProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const client = useClient();
  const player = handle.entity;
  const me = player?.ref;
  const { feedbackFor, press } = usePressFeedback(me);
  const [ask, setAsk] = useState<{ title: string; text: string; go: () => void } | null>(null);
  const fallback = fallbackName(entity);
  const own = name ?? player?.name ?? fallback;
  // What the speakers on offer play, to tell the ones with a stream of their own from the quiet ones.
  const offered = offeredSpeakers(player?.groupable, speakers).filter((ref) => ref !== me);
  const offeredStates = useEntities(offered);

  if (handle.status !== 'ready' || !player || !me) {
    return (
      <Flex direction="column" gap={2} aria-busy="true">
        {handle.status === 'ready' || handle.status === 'loading' ? (
          [0, 1, 2].map((row) => <Skeleton key={row} width="100%" height={44} radius="row" />)
        ) : (
          <Typography as="p" variant="body" color="textMuted">
            {statusLabels[handle.status]}
          </Typography>
        )}
      </Flex>
    );
  }

  if (!player.capabilities.group) {
    return (
      <Typography as="p" variant="body" color="textMuted">
        {own} cannot be grouped with other speakers.
      </Typography>
    );
  }

  const group = player.group;
  const follows = group !== undefined && group.leader !== me;
  // Where the changes to the stream go: to the player it belongs to, which is this one unless it follows.
  const head = group?.leader ?? me;
  const inStream = group ? (follows ? [group.leader, ...group.members] : group.members) : [];
  const others = inStream.filter((ref) => ref !== me);
  const candidates = offered.filter((ref) => !others.includes(ref));
  const playsOwn = (ref: EntityRef) => {
    const state = offeredStates[offered.indexOf(ref)];
    return (
      state?.kind === 'mediaPlayer' &&
      state.availability === 'ready' &&
      (state.playback === 'playing' || state.group !== undefined)
    );
  };

  const elsewhere = candidates.filter(playsOwn);
  const quiet = candidates.filter((ref) => !playsOwn(ref));

  const send = (target: EntityRef, command: string, args?: Record<string, unknown>) =>
    client.command(target, command, args);

  const reset = () =>
    press('group:reset', () =>
      follows
        ? send(me, 'leaveGroup')
        : send(me, 'setGroupMembers', { remove: group?.members ?? [] }),
    );

  const addHere = (speaker: Speaker) => {
    const go = () =>
      press(`group:add:${speaker.ref}`, () =>
        send(head, 'setGroupMembers', { add: [speaker.ref] }),
      );

    if (speaker.playing === undefined) {
      go();
      return;
    }

    setAsk({
      title: `Play this on ${speaker.name}?`,
      text: `${speaker.name} is playing ${speaker.playing}. It will play this stream instead.`,
      go,
    });
  };

  const join = (speaker: Speaker) => {
    const go = () =>
      press(`group:join:${speaker.ref}`, () =>
        send(speaker.leader ?? speaker.ref, 'setGroupMembers', { add: [me] }),
      );

    if (player.playback !== 'playing') {
      go();
      return;
    }

    setAsk({
      title: `Join ${speaker.name}'s stream?`,
      text: `${own} will stop what it plays and play ${speaker.name}'s instead.`,
      go,
    });
  };

  return (
    <Flex
      direction="column"
      gap={4}
      grow={1}
      minHeight={0}
      css={{ overflowY: 'auto', overflowX: 'hidden' }}
    >
      {!follows && others.length > 1 ? (
        <Flex justify="flex-end">
          <PillButton
            label="Remove all"
            icon="lu:x"
            description={`Take everyone else out of this stream, back to just ${own}`}
            state={feedbackFor('group:reset')}
            onClick={reset}
          />
        </Flex>
      ) : null}

      <Section title="This stream">
        <SpeakerRow speaker={me} label={own} volume handle={handle}>
          {() =>
            follows ? (
              <PillButton
                label="Leave"
                icon="lu:log-out"
                description={`Take ${own} out of this stream`}
                state={feedbackFor('group:reset')}
                onClick={reset}
              />
            ) : null
          }
        </SpeakerRow>
        {others.map((ref) => (
          <SpeakerRow
            key={ref}
            speaker={ref}
            volume
            note={ref === group?.leader ? 'Leading' : undefined}
          >
            {(speaker) =>
              ref === group?.leader ? (
                <PillButton
                  label="Remove"
                  icon="lu:x"
                  description={`Take ${speaker.name} out of this stream and carry on here`}
                  state={feedbackFor('group:takeover')}
                  onClick={() => press('group:takeover', () => send(me, 'takeOverGroup'))}
                />
              ) : (
                <PillButton
                  label="Remove"
                  icon="lu:x"
                  description={`Take ${speaker.name} out of this stream`}
                  state={feedbackFor(`group:remove:${ref}`)}
                  onClick={() =>
                    press(`group:remove:${ref}`, () =>
                      send(head, 'setGroupMembers', { remove: [ref] }),
                    )
                  }
                />
              )
            }
          </SpeakerRow>
        ))}
      </Section>

      {elsewhere.length > 0 ? (
        <Section title="Playing elsewhere">
          {elsewhere.map((ref) => (
            <SpeakerRow key={ref} speaker={ref}>
              {(speaker) => (
                <>
                  <PillButton
                    label="Join"
                    icon="lu:log-in"
                    description={`Join ${speaker.name}'s stream`}
                    disabled={!speaker.available}
                    state={feedbackFor(`group:join:${ref}`)}
                    onClick={() => join(speaker)}
                  />
                  <PillButton
                    label="Add"
                    icon="lu:plus"
                    description={`Add ${speaker.name} to this stream`}
                    disabled={!speaker.available}
                    state={feedbackFor(`group:add:${ref}`)}
                    onClick={() => addHere(speaker)}
                  />
                </>
              )}
            </SpeakerRow>
          ))}
        </Section>
      ) : null}

      <Section title="Add to this stream">
        {quiet.length === 0 ? (
          <Typography as="p" variant="body" color="textMuted">
            No other speakers to add.
          </Typography>
        ) : (
          quiet.map((ref) => (
            <SpeakerRow key={ref} speaker={ref}>
              {(speaker) => (
                <PillButton
                  label="Add"
                  icon="lu:plus"
                  description={`Add ${speaker.name} to this stream`}
                  disabled={!speaker.available}
                  state={feedbackFor(`group:add:${ref}`)}
                  onClick={() => addHere(speaker)}
                />
              )}
            </SpeakerRow>
          ))
        )}
      </Section>

      <ConfirmDialog
        open={ask !== null}
        title={ask?.title ?? ''}
        description={ask?.text}
        confirmLabel="Go ahead"
        onConfirm={() => {
          ask?.go();
          setAsk(null);
        }}
        onCancel={() => setAsk(null)}
      />
    </Flex>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Flex direction="column" gap={1}>
      <Typography as="h4" variant="eyebrow" uppercase color="textMuted">
        {title}
      </Typography>
      {children}
    </Flex>
  );
}

/** One speaker: its picture, name and what it is doing, and whatever its actions are. With `volume` (a speaker already
 * in the stream) it is just its name, a note like "Leading", its actions, and a volume bar under them: what it plays
 * is the stream's, so neither the picture nor the title is shown again. */
function SpeakerRow({
  speaker,
  label,
  volume = false,
  note,
  handle: given,
  children,
}: {
  speaker: EntityRef;

  /** A name to use in place of the player's own. */
  label?: string;

  /** Shows a volume bar for it. */
  volume?: boolean;

  /** Said after what it is doing. */
  note?: string | undefined;

  /** Its handle, when the caller has one. */
  handle?: EntityHandle<'mediaPlayer'>;
  children: (speaker: Speaker) => ReactNode;
}) {
  const own = useEntityHandle('mediaPlayer', given ?? speaker);
  const player = own.entity;
  const level = useVolumeControl(own);
  const available = own.status === 'ready';
  const title = player?.media?.title;
  const info: Speaker = {
    ref: speaker,
    name: label ?? player?.name ?? fallbackName(speaker),
    available,
    playing: available && player?.playback === 'playing' ? (title ?? 'something') : undefined,
    leader: player?.group?.leader === speaker ? undefined : player?.group?.leader,
  };

  const status = !available
    ? statusLabels[own.status as Exclude<typeof own.status, 'ready'>]
    : [
        info.playing !== undefined
          ? `Playing ${info.playing}`
          : player?.playback === 'paused'
            ? 'Paused'
            : 'Idle',
        note,
      ]
        .filter(Boolean)
        .join(' · ');

  if (volume) {
    return (
      <Flex direction="column" gap={2} py={2} css={{ opacity: available ? 1 : 0.55 }}>
        <Flex align="center" gap={3}>
          <Flex align="baseline" gap={2} grow={1} minWidth={0}>
            <Typography
              as="span"
              variant="bodyStrong"
              noWrap
              textOverflow="ellipsis"
              overflow="hidden"
            >
              {info.name}
            </Typography>
            {!available || note ? (
              <Typography as="span" variant="secondary" color="textMuted" css={{ flex: 'none' }}>
                {available ? note : status}
              </Typography>
            ) : null}
          </Flex>
          <Flex align="center" gap={2} css={{ flex: 'none' }}>
            {children(info)}
          </Flex>
        </Flex>
        {available && player?.capabilities.volume ? (
          <Flex align="center" gap={2}>
            <ValueBar
              label={`${info.name} volume`}
              value={level.shown}
              min={0}
              max={100}
              keyStep={5}
              height={10}
              onDrag={level.drag}
              onCommit={level.commit}
            />
            <Typography
              as="span"
              variant="secondary"
              color="textMuted"
              minWidth={32}
              align="right"
              css={{ flex: 'none', fontVariantNumeric: 'tabular-nums' }}
            >
              {level.shown}%
            </Typography>
          </Flex>
        ) : null}
      </Flex>
    );
  }

  return (
    <Flex align="center" gap={3} py={1.5} css={{ opacity: available ? 1 : 0.55 }}>
      <Flex
        align="center"
        justify="center"
        background="surfaceRaised"
        color="textMuted"
        radius="small"
        width={40}
        height={40}
        overflow="hidden"
        css={{ flex: 'none' }}
      >
        {player?.media?.artworkUrl && info.playing !== undefined ? (
          <Cover src={player.media.artworkUrl} size="small" />
        ) : (
          <Icon name="lu:speaker" size={18} />
        )}
      </Flex>
      <Flex direction="column" grow={1} minWidth={0}>
        <Typography as="span" variant="bodyStrong" noWrap textOverflow="ellipsis" overflow="hidden">
          {info.name}
        </Typography>
        <Typography
          as="span"
          variant="secondary"
          color="textMuted"
          noWrap
          textOverflow="ellipsis"
          overflow="hidden"
        >
          {status}
        </Typography>
      </Flex>
      <Flex align="center" gap={2} css={{ flex: 'none' }}>
        {children(info)}
      </Flex>
    </Flex>
  );
}

/** A small text button for what a speaker does in the stream: "Leave", "Join" or "Add", with a small icon and the
 * pending ring or the error colour the press asks for. Its full sentence is the accessible name. */
function PillButton({
  label,
  icon,
  description,
  state,
  disabled = false,
  onClick,
}: {
  label: string;
  icon: IconName;

  /** What it does in full, for a screen reader and the tooltip: "Join Patio's stream". */
  description?: string;
  state: 'pending' | 'error' | undefined;
  disabled?: boolean;
  onClick: () => void;
}) {
  const busy = state === 'pending';
  return (
    <Flex
      as="button"
      type="button"
      aria-label={description ?? label}
      title={description ?? label}
      onClick={onClick}
      disabled={disabled || busy}
      align="center"
      justify="center"
      gap={1.5}
      radius="full"
      height={32}
      px={3.5}
      background="surfaceRaised"
      color={state === 'error' ? 'danger' : 'text'}
      cursor={disabled ? 'default' : busy ? 'progress' : 'pointer'}
      css={{ flex: 'none', border: 0, opacity: disabled ? 0.5 : 1 }}
    >
      {busy ? <Spinner size={14} /> : <Icon name={icon} size={14} />}
      <Typography as="span" variant="label">
        {label}
      </Typography>
    </Flex>
  );
}

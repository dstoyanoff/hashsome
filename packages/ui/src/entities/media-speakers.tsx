import type { EntityRef } from '@hashsome/core';
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
import { offeredSpeakers } from './offered-speakers.ts';
import styles from './media-speakers.module.css';

export { offeredSpeakers };

export interface MediaSpeakersProps {
  /** The player whose stream this is, as a ref like `ma:porch` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'mediaPlayer'>;

  /** What to call the player. Defaults to the player's own name. */
  name?: string;

  /** An allowlist of the speakers that can be added to this stream (or joined). Only those that are in it and that the player can be grouped with are offered; a speaker the player cannot be grouped with is left out, and one that is but is unavailable shows dimmed. Absent, every speaker it can be grouped with is offered. Speakers already in the stream are always shown, so they can be taken out. */
  speakers?: EntityRef[];
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
      <div className={styles.skeletons} aria-busy="true">
        {handle.status === 'ready' || handle.status === 'loading' ? (
          [0, 1, 2].map((row) => <Skeleton key={row} width="100%" height={44} radius="row" />)
        ) : (
          <p className={styles.note}>{statusLabels[handle.status]}</p>
        )}
      </div>
    );
  }

  if (!player.capabilities.group) {
    return <p className={styles.note}>{own} cannot be grouped with other speakers.</p>;
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

  // A follower leaves the stream, which goes on without it.
  const leave = () => press('group:reset', () => send(me, 'leaveGroup'));

  // A leader cannot just leave: the stream is its own. It hands it to a speaker that follows, which takes it over (the
  // old leader leaves and stops, the rest go on with it). With one other speaker that is who gets it, so leaving is
  // one press; with several each one has a button to lead, and the leader picks.
  const handsOver = !follows && others.length > 0;
  const soleHeir = handsOver && others.length === 1 ? others[0] : undefined;
  const heirName = soleHeir ? offeredStates[offered.indexOf(soleHeir)]?.name : undefined;
  const handOver = (heir: EntityRef) =>
    press(heir === soleHeir ? 'group:handover' : `group:lead:${heir}`, () =>
      send(heir, 'takeOverGroup'),
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
    <div className={styles.speakers}>
      <Section title="This stream">
        <SpeakerRow speaker={me} label={own} volume handle={handle}>
          {() =>
            follows ? (
              <PillButton
                label="Leave"
                icon="lu:log-out"
                description={`Take ${own} out of this stream`}
                state={feedbackFor('group:reset')}
                onClick={leave}
              />
            ) : soleHeir ? (
              <PillButton
                label="Leave"
                icon="lu:log-out"
                description={`Leave this stream; ${heirName ?? 'the other speaker'} carries on and leads it`}
                state={feedbackFor('group:handover')}
                onClick={() => handOver(soleHeir)}
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
                <>
                  {handsOver && !soleHeir ? (
                    <PillButton
                      label="Lead"
                      icon="lu:crown"
                      description={`Hand this stream to ${speaker.name}; ${own} leaves it`}
                      state={feedbackFor(`group:lead:${ref}`)}
                      onClick={() => handOver(ref)}
                    />
                  ) : null}
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
                </>
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
          <p className={styles.note}>No other speakers to add.</p>
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
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className={styles.section}>
      <h4 className={styles.sectionTitle}>{title}</h4>
      {children}
    </div>
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
      <div className={styles.volumeRow} data-available={available}>
        <div className={styles.head}>
          <div className={styles.names}>
            <span className={styles.name}>{info.name}</span>
            {!available || note ? (
              <span className={styles.aside}>{available ? note : status}</span>
            ) : null}
          </div>
          <div className={styles.actions}>{children(info)}</div>
        </div>
        {available && player?.capabilities.volume ? (
          <div className={styles.level}>
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
            <span className={styles.percent}>{level.shown}%</span>
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div className={styles.row} data-available={available}>
      <div className={styles.picture}>
        {player?.media?.artworkUrl && info.playing !== undefined ? (
          <Cover src={player.media.artworkUrl} size="small" />
        ) : (
          <Icon name="lu:speaker" size={18} />
        )}
      </div>
      <div className={styles.text}>
        <span className={styles.name}>{info.name}</span>
        <span className={styles.status}>{status}</span>
      </div>
      <div className={styles.actions}>{children(info)}</div>
    </div>
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
    <button
      type="button"
      className={styles.pill}
      data-state={state}
      aria-label={description ?? label}
      title={description ?? label}
      onClick={onClick}
      disabled={disabled || busy}
    >
      {busy ? <Spinner size={14} /> : <Icon name={icon} size={14} />}
      {label}
    </button>
  );
}

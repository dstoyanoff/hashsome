/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import { Box, Flex, Typography } from 'e-prim';
import { motion } from 'motion/react';
import { useId, useState, type ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { useDetail } from '../layout/detail-provider.tsx';
import { Icon } from '../icon.tsx';
import { MediaSpeakers } from './media-speakers.tsx';
import { SpeakersButton, SpeakersOverlay, SpeakersPill } from './media-speakers-pill.tsx';
import { IconButton } from '../layout/tile.tsx';
import { NowPlaying } from './now-playing.tsx';
import { PlayerSwitcher, type PlayerOption } from './player-switcher.tsx';

/** The artwork ring's diameter in a full-size player, against the usual 168px. */
const FULL_SIZE = 260;

export interface MediaPlayerFullProps {
  /** The media player, as a ref like `ma:living_room`. */
  entity: EntityRef | EntityHandle<'mediaPlayer'>;

  /** What goes below the player, usually the player's library: `<MediaBrowser entity="ma:living_room" layout="theater" />`. Left out, only the player is drawn. */
  browser?: ReactNode;

  /** What goes beside the player in a wide space, usually the player's queue: `<MediaQueue entity="ma:living_room" />`. Only used when `wide` and the player has a queue; the narrow layout has no room for it. */
  queue?: ReactNode;

  /** Whether the player can add speakers to what it plays, put it back to just itself and join a stream playing elsewhere (`MediaSpeakers`): a pill under the track info says how its speakers stand and opens them. Only for a player given as a ref that can be grouped. `false` leaves it out. Default `true`. */
  grouping?: boolean;

  /** An allowlist of the speakers that can be added: only those that are in it and that the player can be grouped with are offered. Absent, every speaker it can be grouped with is offered. */
  speakers?: EntityRef[];

  /** How the pill opens the speakers: `overlay` over the player, in the side panel the media overlays use, or `inline`, in the place of the list below the player, for a player that is already in a panel. Default `overlay`. */
  speakersView?: 'overlay' | 'inline';

  /** Calls the player this instead of the name it reports. */
  name?: string;

  /** Gives the library the whole width, for one laid out to use it (the theater layout). Defaults to whether the drawer is expanded. */
  wide?: boolean;

  /** In the narrow layout, where the queue has no room beside the player, a Library and a Queue tab above the list switch between them (shown when there is a `browser`, a `queue` and a player with a queue). `false` leaves the library alone, as before. In the wide layout the queue is beside the player and there are no tabs. Default `true`. */
  tabs?: boolean;

  /** The tab that is open first, when the tabs are on. Default `'library'`. */
  defaultTab?: ListTab;

  /** The open tab, to keep it yourself (to remember it between visits, or open the queue from elsewhere); the tabs ask for a change through `onTabChange`. Without it, the component keeps it, until it is gone. */
  tab?: ListTab;

  /** Called with the tab someone chose. */
  onTabChange?: (tab: ListTab) => void;

  /** Volume and speakers as round icon buttons on either side of the transport instead of a slider and a pill under it, which makes the player shorter: the speakers button on the left opens them like the pill does, and the volume button on the right opens a popover with the slider. Default `false`. */
  compact?: boolean;

  /** The players this one can be swapped for, with the one shown among them: a small text dropdown in the corner of the player, next to the artwork, names the speaker and lists the others. Needs `onPlayerChange` and a player given as a ref, and shows only with more than one. Left out, there is no dropdown. */
  players?: PlayerOption[];

  /** Called with the player someone picked from `players`; the page swaps `entity` for it. */
  onPlayerChange?: (entity: EntityRef) => void;
}

/** What the tabs below the player choose between. */
export type ListTab = 'library' | 'queue';

const TAB_HEIGHT = 40;

/** Library and Queue, as text with an underline (not the pills the library's own categories are, so the
 * two rows read as different things). */
function ListTabs({
  value,
  lists,
  onChange,
}: {
  value: ListTab;
  lists: ListTab[];
  onChange: (tab: ListTab) => void;
}) {
  // One line that slides between the tabs; its own id, so two players on a page do not share it.
  const line = useId();
  const all: { id: ListTab; label: string; icon: 'lu:library' | 'lu:list-music' }[] = [
    { id: 'library', label: 'Library', icon: 'lu:library' },
    { id: 'queue', label: 'Queue', icon: 'lu:list-music' },
  ];

  const tabs = all.filter((tab) => lists.includes(tab.id));

  return (
    <Flex
      role="tablist"
      aria-label="Library or queue"
      gap={5}
      css={({ palette }) => ({ flexShrink: 0, borderBottom: `1px solid ${palette.border}` })}
    >
      {tabs.map((tab) => {
        const selected = tab.id === value;
        return (
          <Flex
            as="button"
            type="button"
            key={tab.id}
            role="tab"
            aria-selected={selected}
            onClick={() => onChange(tab.id)}
            align="center"
            justify="center"
            gap={2}
            height={TAB_HEIGHT}
            cursor="pointer"
            color={selected ? 'text' : 'textMuted'}
            css={{
              flex: 1,
              background: 'none',
              border: 0,
              padding: 0,
              position: 'relative',
              marginBottom: -1,
            }}
          >
            {/* The line under the open tab sits on the row's own line. */}
            {selected ? (
              <Box
                as={motion.span}
                layoutId={line}
                position="absolute"
                background="accent"
                transition={{ duration: 0.2, ease: 'easeOut' }}
                css={{ left: 0, right: 0, bottom: 0, height: 2 }}
              />
            ) : null}
            <Icon name={tab.icon} size={16} />
            <Typography as="span" variant={selected ? 'bodyStrong' : 'body'}>
              {tab.label}
            </Typography>
          </Flex>
        );
      })}
    </Flex>
  );
}

/** The big player: the player centered at the top, and the library (or whatever `browser` is) below
 * it. It is what the media cards' drawers show, at every width, and it is the widget a dashboard puts
 * in a page of its own, around it whatever it likes (a surface that fills the space and scrolls, a
 * heading, other cards beside it). It fills the height it is given, so the page that holds it
 * decides how tall that is. */
export function MediaPlayerFull({
  entity,
  browser,
  queue,
  grouping = true,
  speakers,
  speakersView = 'overlay',
  wide,
  name,
  tabs = true,
  defaultTab = 'library',
  tab,
  onTabChange,
  compact = false,
  players,
  onPlayerChange,
}: MediaPlayerFullProps) {
  const handle = useEntityHandle('mediaPlayer', entity);
  const { detail } = useDetail();
  const full = wide ?? detail?.expanded === true;

  // Narrow, the queue and the library take turns in the one space below the player.
  const [kept, setKept] = useState<ListTab>(defaultTab);
  const hasQueue = queue !== undefined && handle.entity?.capabilities.queue === true;
  const lists: ListTab[] = [
    ...(browser !== undefined ? (['library'] as const) : []),
    ...(!full && tabs && hasQueue ? (['queue'] as const) : []),
  ];

  // The speakers, behind a pill under the track info.
  const ref = typeof entity === 'string' ? entity : undefined;
  const player = handle.entity;
  const [speakersOpen, setSpeakersOpen] = useState(false);
  const speakersBody =
    grouping && ref && player?.capabilities.group === true ? (
      <MediaSpeakers
        entity={ref}
        {...(name !== undefined ? { name } : {})}
        {...(speakers ? { speakers } : {})}
      />
    ) : undefined;

  const title = name ?? player?.name ?? fallbackName(entity);
  const pill =
    speakersBody && ref ? (
      compact ? undefined : speakersView === 'inline' ? (
        <SpeakersPill
          player={player}
          speakers={speakers}
          pressed={speakersOpen}
          onClick={() => setSpeakersOpen((now) => !now)}
        />
      ) : (
        <SpeakersOverlay
          player={player}
          entity={ref}
          name={name}
          title={title}
          allowed={speakers}
        />
      )
    ) : undefined;

  // Compact, the speakers are a round button at the left of the transport row, opposite the volume's.
  const speakersButton =
    compact && speakersBody && ref ? (
      speakersView === 'inline' ? (
        <IconButton
          icon="lu:speaker"
          label="Speakers"
          glyph={18}
          pressed={speakersOpen}
          onClick={() => setSpeakersOpen((now) => !now)}
        />
      ) : (
        <SpeakersButton player={player} entity={ref} name={name} title={title} allowed={speakers} />
      )
    ) : undefined;

  const switcher =
    ref && players && players.length > 1 && onPlayerChange ? (
      <PlayerSwitcher players={players} current={ref} onChange={onPlayerChange} />
    ) : undefined;

  const showSpeakers = speakersView === 'inline' && speakersOpen && speakersBody !== undefined;
  const wanted = tab ?? kept;
  const open: ListTab | undefined = lists.includes(wanted) ? wanted : lists[0];
  const choose = (next: ListTab) => {
    setKept(next);
    onTabChange?.(next);
  };

  return (
    <Flex direction="column" gap={5} grow={1} minHeight={0}>
      {/* Full size, the player is larger and centered in whatever room the library leaves, with the
          library docked to the bottom. In the narrower drawer everything just stacks from the top. */}
      <Flex
        align="stretch"
        justify="center"
        gap={6}
        {...(full ? { grow: 1 } : {})}
        css={{ flexShrink: 0 }}
      >
        <Flex align="center" justify="center" grow={1} minWidth={0} position="relative">
          {/* In the corner, over the room the round artwork leaves, so it costs no height. */}
          {switcher ? (
            <Box position="absolute" css={{ top: 0, left: 0 }}>
              {switcher}
            </Box>
          ) : null}
          <Box width="100%" maxWidth={full ? 560 : 420}>
            <NowPlaying
              handle={handle}
              fallback={fallbackName(entity)}
              {...(name !== undefined ? { name } : {})}
              shuffle="title"
              {...(pill ? { speakers: pill } : {})}
              {...(speakersButton ? { leading: speakersButton } : {})}
              {...(compact ? { volumeControl: 'icon' as const } : {})}
              {...(full ? { size: FULL_SIZE } : {})}
            />
          </Box>
        </Flex>
        {full && queue !== undefined && handle.entity?.capabilities.queue === true ? (
          // Beside the player, the whole height of the room it has, with a quiet line between them. The
          // queue is laid over that room rather than in it, so a long one scrolls inside it and never
          // makes the room taller.
          <Flex
            position="relative"
            width={400}
            css={({ palette }) => ({ flex: 'none', borderLeft: `1px solid ${palette.border}` })}
          >
            <Flex direction="column" position="absolute" pl={5} css={{ inset: 0 }}>
              {queue}
            </Flex>
          </Flex>
        ) : null}
      </Flex>
      {showSpeakers ? (
        <>
          <Box height={1} background="border" css={{ flexShrink: 0 }} />
          <Flex
            direction="column"
            width="100%"
            minHeight={0}
            {...(full ? {} : { maxWidth: 960, mx: 'auto' })}
          >
            {speakersBody}
          </Flex>
        </>
      ) : open !== undefined ? (
        <>
          {/* The tabs are the line between the player and the list when there are two; one list has a plain line. */}
          {lists.length > 1 ? (
            <ListTabs value={open} lists={lists} onChange={choose} />
          ) : (
            <Box height={1} background="border" css={{ flexShrink: 0 }} />
          )}
          {/* A list is capped so a row is never a long way from its play button on a very wide
              screen; the theater row uses the whole width. The player stays put and the library
              is what scrolls, inside the space the drawer leaves it. */}
          <Flex
            // Another list fades in where the last one was, so a tab does not cut from one to the other.
            as={motion.div}
            key={open}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ duration: 0.15, ease: 'easeOut' }}
            direction="column"
            width="100%"
            minHeight={0}
            {...(full ? {} : { maxWidth: 960, mx: 'auto' })}
          >
            {open === 'queue' ? queue : browser}
          </Flex>
        </>
      ) : null}
    </Flex>
  );
}

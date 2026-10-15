/** @jsxImportSource @emotion/react */
import type { LogbookEntry } from '@hashsome/core';
import { Flex, Typography } from 'e-prim';
import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useState } from 'react';
import { Icon } from '../icon.tsx';
import { capitalize } from '../status.ts';
import { PlainButton } from './plain-button.tsx';
import { Skeleton } from './skeleton.tsx';

export type { LogbookEntry };

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < MINUTE) {
    return 'Just now';
  }

  if (diff < HOUR) {
    return `${Math.round(diff / MINUTE)}m ago`;
  }

  if (diff < DAY) {
    return `${Math.round(diff / HOUR)}h ago`;
  }

  return `${Math.round(diff / DAY)}d ago`;
}

function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  return words
    .slice(0, 2)
    .map((word) => word.charAt(0).toUpperCase())
    .join('');
}

/** The chip for a row nobody caused: a switch thrown the way it went for on and off, and a chip for
 * the device coming or going or another mode. */
const QUIET_ICON = {
  on: 'lu:toggle-right',
  off: 'lu:toggle-left',
  state: 'lu:cpu',
  availability: 'lu:cpu',
} as const;

/** The round picture at the start of a row: initials for a person, a workflow icon for an automation, and
 * for nobody a quiet chip, so every row's text lines up: a switch for on and off (often someone at
 * the device's own switch), a chip for the device coming or going. */
function Avatar({
  actor,
  kind,
  change,
}: {
  actor: string | undefined;
  kind: 'person' | 'automation';
  change: LogbookEntry['change'];
}) {
  const person = actor !== undefined && kind === 'person';
  return (
    <Flex
      role="img"
      aria-label={actor ?? 'System'}
      align="center"
      justify="center"
      background={person ? 'accent' : 'surfaceRaised'}
      color={person ? 'accentText' : actor === undefined ? 'textMuted' : 'text'}
      radius="full"
      width={32}
      height={32}
      css={{ flex: 'none' }}
    >
      {person ? (
        <Typography as="span" variant="eyebrow">
          {initials(actor)}
        </Typography>
      ) : (
        <Icon
          name={actor !== undefined ? 'lu:workflow' : QUIET_ICON[change ?? 'availability']}
          size={16}
        />
      )}
    </Flex>
  );
}

export interface HistorySectionProps {
  /** Recent activity, newest first. `undefined` until it is known. An empty list from a caller that
   * has no way to ask for more (no `onOpen`) renders nothing. */
  entries: LogbookEntry[] | undefined;

  /** The entries are on their way: the open section shows placeholder rows. */
  loading?: boolean;

  /** Called when the section is opened, so whoever owns the entries can go and get them. */
  onOpen?: () => void;
}

const SKELETON_ROWS = 3;

function SkeletonRows() {
  return (
    <Flex direction="column" aria-busy="true" aria-label="Loading history">
      {Array.from({ length: SKELETON_ROWS }, (_, row) => (
        <Flex key={row} align="center" gap={3} py={2}>
          <Skeleton width={32} height={32} radius="full" />
          <Flex direction="column" gap={1}>
            <Skeleton width={row === 1 ? 150 : 190} height={14} />
            <Skeleton width={56} height={12} />
          </Flex>
        </Flex>
      ))}
    </Flex>
  );
}

/** A short activity list for an entity's detail drawer, folded away until asked for. Generic — any
 * entity type can supply it. */
export function HistorySection({ entries, loading = false, onOpen }: HistorySectionProps) {
  const [open, setOpen] = useState(false);
  const still = useReducedMotion();
  if (entries?.length === 0 && !onOpen) {
    return null;
  }

  const toggle = () => {
    if (!open) {
      onOpen?.();
    }

    setOpen(!open);
  };

  return (
    <Flex direction="column" gap={2} pt={4} mt="auto">
      <PlainButton
        aria-expanded={open}
        onClick={toggle}
        align="center"
        justify="space-between"
        color="textMuted"
        py={1}
      >
        <Typography as="span" variant="eyebrow" uppercase color="textMuted">
          History
        </Typography>
        <motion.span
          css={{ display: 'grid' }}
          initial={false}
          animate={{ rotate: open ? 180 : 0 }}
          transition={{ duration: still ? 0 : 0.2, ease: 'easeOut' }}
        >
          <Icon name="lu:chevron-down" size={16} />
        </motion.span>
      </PlainButton>
      <AnimatePresence initial={false}>
        {open ? (
          <motion.div
            key="body"
            css={{ overflow: 'hidden' }}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: still ? 0 : 0.22, ease: 'easeOut' }}
          >
            {loading ? (
              <SkeletonRows />
            ) : !entries?.length ? (
              <Typography as="span" variant="secondary" color="textMuted">
                No recent activity
              </Typography>
            ) : (
              <Flex direction="column">
                {entries.map((entry) => (
                  <Flex key={entry.id} align="center" gap={3} py={2}>
                    <Avatar
                      actor={entry.actor}
                      kind={entry.actorKind ?? 'person'}
                      change={entry.change}
                    />
                    <Flex direction="column" minWidth={0}>
                      <Typography
                        as="span"
                        variant="body"
                        noWrap
                        textOverflow="ellipsis"
                        minWidth={0}
                      >
                        {entry.actor ? (
                          <Typography as="span" variant="bodyStrong">
                            {entry.actor}{' '}
                          </Typography>
                        ) : null}
                        {entry.actor ? entry.message : capitalize(entry.message)}
                      </Typography>
                      <Typography as="span" variant="secondary" color="textMuted">
                        {relativeTime(entry.timestamp)}
                      </Typography>
                    </Flex>
                  </Flex>
                ))}
              </Flex>
            )}
          </motion.div>
        ) : null}
      </AnimatePresence>
    </Flex>
  );
}

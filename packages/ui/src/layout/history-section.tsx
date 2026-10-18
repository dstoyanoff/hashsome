import { ChevronDown, Cpu, ToggleLeft, ToggleRight, Workflow } from 'lucide-react';
import type { LogbookEntry } from '@hashsome/core';
import { m, Presence } from '../motion/index.tsx';
import { useState } from 'react';
import { Icon } from '../icon.tsx';
import { capitalize } from '../status.ts';
import styles from './history-section.module.css';
import { PlainButton } from './plain-button.tsx';
import { Skeleton } from './skeleton.tsx';
import { useStill } from './use-still.ts';

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
  on: <ToggleRight />,
  off: <ToggleLeft />,
  state: <Cpu />,
  availability: <Cpu />,
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
    <span
      className={styles.avatar}
      role="img"
      aria-label={actor ?? 'System'}
      data-who={person ? 'person' : actor === undefined ? 'system' : 'automation'}
    >
      {person ? (
        <span className={styles.initials}>{initials(actor)}</span>
      ) : (
        <Icon size={16}>
          {actor !== undefined ? <Workflow /> : QUIET_ICON[change ?? 'availability']}
        </Icon>
      )}
    </span>
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
    <div className={styles.skeletons} aria-busy="true" aria-label="Loading history">
      {Array.from({ length: SKELETON_ROWS }, (_, row) => (
        <div key={row} className={styles.skeletonRow}>
          <Skeleton width={32} height={32} radius="full" />
          <div className={styles.skeletonText}>
            <Skeleton width={row === 1 ? 150 : 190} height={14} />
            <Skeleton width={56} height={12} />
          </div>
        </div>
      ))}
    </div>
  );
}

/** A short activity list for an entity's detail drawer, folded away until asked for. Generic — any
 * entity type can supply it. */
export function HistorySection({ entries, loading = false, onOpen }: HistorySectionProps) {
  const [open, setOpen] = useState(false);
  const still = useStill();
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
    <div className={styles.section}>
      <PlainButton className={styles.toggle} aria-expanded={open} onClick={toggle}>
        <span className={styles.heading}>History</span>
        <m.span
          className={styles.chevron}
          initial={false}
          animate={{ rotate: open ? 180 : 0 }}
          transition={{ duration: still ? 0 : 0.2, ease: 'easeOut' }}
        >
          <Icon size={16}>
            <ChevronDown />
          </Icon>
        </m.span>
      </PlainButton>
      <Presence initial={false}>
        {open ? (
          <m.div
            key="body"
            className={styles.body}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: 'auto', opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={{ duration: still ? 0 : 0.22, ease: 'easeOut' }}
          >
            {loading ? (
              <SkeletonRows />
            ) : !entries?.length ? (
              <span className={styles.empty}>No recent activity</span>
            ) : (
              <div className={styles.list}>
                {entries.map((entry) => (
                  <div key={entry.id} className={styles.entry}>
                    <Avatar
                      actor={entry.actor}
                      kind={entry.actorKind ?? 'person'}
                      change={entry.change}
                    />
                    <div className={styles.entryText}>
                      <span className={styles.message}>
                        {entry.actor ? <span className={styles.actor}>{entry.actor} </span> : null}
                        {entry.actor ? entry.message : capitalize(entry.message)}
                      </span>
                      <span className={styles.when}>{relativeTime(entry.timestamp)}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </m.div>
        ) : null}
      </Presence>
    </div>
  );
}

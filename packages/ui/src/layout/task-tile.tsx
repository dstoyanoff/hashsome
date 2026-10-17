/** @jsxImportSource @emotion/react */
import { Flex, Typography } from '../style/prim.tsx';
import { useEffect, useState } from 'react';
import type { IconName } from '../icon-data.ts';
import type { EntityStatus } from '../status.ts';
import { ConfirmDialog } from './confirm-dialog.tsx';
import { IconButton, Tile } from './tile.tsx';

/** Where a recurring task stands: not due for a while, due before long, or past its date. */
export type TaskState = 'ok' | 'dueSoon' | 'overdue';

export interface TaskTileProps {
  /** What is to be done, e.g. `Septic additive`. */
  label: string;

  /** Icon id, e.g. `lu:wrench`. */
  icon?: IconName;

  /** Where the task stands. Whoever has the task decides it: how soon is "soon" is different for a weekly task and a yearly one. */
  state: TaskState;

  /** When it is next due. Shown as "Due in 2 days" or "Overdue by 3 days". */
  dueAt?: Date | undefined;

  /** When it was last done, shown in the question that marks it done again. */
  lastDoneAt?: Date | undefined;

  /** Status of the thing behind it, as for `Tile`: anything but `ready` shows why instead and disables the tile. */
  status?: EntityStatus;

  /** The person confirmed it is done, on this day (today unless they chose another, in case it was forgotten at the time). Rejecting shows the error in the dialog and leaves it open. */
  onComplete: (doneOn: Date) => void | Promise<void>;
}

const DAY_MS = 86_400_000;
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());

/** How many calendar days from `now` to `then`: negative when `then` has passed. */
const daysTo = (then: Date, now: Date) =>
  Math.round((startOfDay(then).getTime() - startOfDay(now).getTime()) / DAY_MS);

const plural = (n: number) => `${n} day${n === 1 ? '' : 's'}`;

/** A day as "Mar 10", with the year ("Mar 10, 2027") when it is not this year's. */
const short = (date: Date, now: Date) =>
  date.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    ...(date.getFullYear() === now.getFullYear() ? {} : { year: 'numeric' }),
  });

/** The line under a task's name. */
export function taskLine(state: TaskState, dueAt: Date | undefined, now: Date): string {
  if (!dueAt) {
    return state === 'overdue' ? 'Overdue' : state === 'dueSoon' ? 'Due soon' : 'Up to date';
  }

  const days = daysTo(dueAt, now);
  if (state === 'ok') {
    return `Next ${short(dueAt, now)}`;
  }

  if (days === 0) {
    return 'Due today';
  }

  if (days > 0) {
    return days === 1 ? 'Due tomorrow' : `Due in ${plural(days)}`;
  }

  return `Overdue by ${plural(-days)}`;
}

/** The palette color of each state's line. */
const TONE = { ok: 'textMuted', dueSoon: 'warm', overdue: 'danger' } as const;

/**
 * A recurring task as a tile: its name, where it stands (up to date, due soon, overdue) and when it is due.
 * Tapping it asks whether it is done, with the day it was done on (today, or an earlier one if it was
 * forgotten), and only a confirmation calls `onComplete`, so a stray tap does nothing.
 */
export function TaskTile({
  label,
  icon,
  state,
  dueAt,
  lastDoneAt,
  status = 'ready',
  onComplete,
}: TaskTileProps) {
  const [open, setOpen] = useState(false);
  // How many days before today it was done: 0 is today.
  const [daysAgo, setDaysAgo] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  // The words under the name follow the clock: "Due tomorrow" becomes "Due today" at midnight.
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 60_000);
    return () => clearInterval(id);
  }, []);

  const ask = () => {
    setDaysAgo(0);
    setError(undefined);
    setOpen(true);
  };

  const confirm = async () => {
    setBusy(true);
    try {
      await onComplete(
        daysAgo === 0 ? new Date() : startOfDay(new Date(now.getTime() - daysAgo * DAY_MS)),
      );

      setOpen(false);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Tile
        label={label}
        {...(icon ? { icon } : {})}
        status={status}
        onPress={ask}
        secondary={
          <Typography
            as="span"
            variant="secondary"
            css={({ colorByKey }) => ({ color: colorByKey(TONE[state]) })}
          >
            {taskLine(state, dueAt, now)}
          </Typography>
        }
      />
      <ConfirmDialog
        open={open}
        title={`Mark “${label}” as done?`}
        description={lastDoneAt ? `Last done ${short(lastDoneAt, now)}.` : undefined}
        confirmLabel="Mark as done"
        busy={busy}
        onConfirm={() => void confirm()}
        onCancel={() => setOpen(false)}
      >
        <Flex direction="column" gap={2}>
          <DayStepper label="Done on" daysAgo={daysAgo} now={now} onChange={setDaysAgo} />
          {error ? (
            <Typography as="span" variant="secondary" color="danger" role="alert">
              {error}
            </Typography>
          ) : null}
        </Flex>
      </ConfirmDialog>
    </>
  );
}

/** A day chosen by stepping back from today one at a time, which is what is wanted for a task that was done a day or two ago and is quicker, and far smaller, than a calendar. It cannot go past today. */
function DayStepper({
  label,
  daysAgo,
  now,
  onChange,
}: {
  label: string;
  daysAgo: number;
  now: Date;
  onChange: (daysAgo: number) => void;
}) {
  const day = new Date(now.getTime() - daysAgo * DAY_MS);
  const words =
    daysAgo === 0
      ? 'Today'
      : daysAgo === 1
        ? 'Yesterday'
        : day.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' });

  return (
    <Flex align="center" justify="space-between" gap={3} role="group" aria-label={label}>
      <Typography as="span" variant="secondary" color="textMuted">
        {label}
      </Typography>
      <Flex align="center" gap={2}>
        <IconButton
          icon="lu:chevron-left"
          label="Previous day"
          glyph={16}
          onClick={() => onChange(daysAgo + 1)}
        />
        <Typography as="span" variant="bodyStrong" css={{ minWidth: 96, textAlign: 'center' }}>
          {words}
        </Typography>
        <IconButton
          icon="lu:chevron-right"
          label="Next day"
          glyph={16}
          disabled={daysAgo === 0}
          onClick={() => onChange(daysAgo - 1)}
        />
      </Flex>
    </Flex>
  );
}

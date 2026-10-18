import { Calendar, ChevronLeft, ChevronRight } from 'lucide-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Icon } from '../icon.tsx';
import styles from './date-time-field.module.css';
import { RoundButton } from './round-button.tsx';
import { fromDateTimeInput, toDateTimeInput } from './sensor-stats.ts';

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const dayKey = (d: Date) => d.getFullYear() * 10_000 + d.getMonth() * 100 + d.getDate();
const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate());

/** The 6×7 grid of days (Monday first) that shows `month`, including the neighbouring months' spill-over. */
function gridDays(month: Date): Date[] {
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const offset = (first.getDay() + 6) % 7;
  return Array.from(
    { length: 42 },
    (_, i) => new Date(first.getFullYear(), first.getMonth(), 1 - offset + i),
  );
}

/**
 * A date + time picker that opens as a popover under its field — not a native `<input type="datetime-local">`,
 * whose popup can't be themed and is slow to appear. Day buttons keep the chosen time of day; the
 * time is two 24-hour number fields. `min` / `max` (`YYYY-MM-DDTHH:mm`) disable days outside the bounds and clamp the result.
 */
export function DateTimeField({
  label,
  value,
  min,
  max,
  align = 'left',
  onChange,
}: {
  label: string;

  /** `YYYY-MM-DDTHH:mm`, local time. */
  value: string;
  min?: string;
  max?: string;

  /** Which edge of the field the popover lines up with. Default `'left'`. */
  align?: 'left' | 'right';
  onChange: (value: string) => void;
}) {
  const current = fromDateTimeInput(value) ?? new Date();
  const lower = min ? fromDateTimeInput(min) : undefined;
  const upper = max ? fromDateTimeInput(max) : undefined;
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }

    const onPointerDown = (event: PointerEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        setOpen(false);
      }
    };

    document.addEventListener('pointerdown', onPointerDown);
    window.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      window.removeEventListener('keydown', onKeyDown, true);
    };
  }, [open]);

  const [month, setMonth] = useState(() => new Date(current.getFullYear(), current.getMonth(), 1));

  const commit = (next: Date) => {
    const clamped = lower && next < lower ? lower : upper && next > upper ? upper : next;
    onChange(toDateTimeInput(clamped));
  };

  const pickDay = (day: Date) =>
    commit(
      new Date(
        day.getFullYear(),
        day.getMonth(),
        day.getDate(),
        current.getHours(),
        current.getMinutes(),
      ),
    );

  const setTime = (hours: number, minutes: number) =>
    commit(new Date(current.getFullYear(), current.getMonth(), current.getDate(), hours, minutes));

  const shiftMonth = (delta: number) =>
    setMonth((m) => new Date(m.getFullYear(), m.getMonth() + delta, 1));

  const dayDisabled = (day: Date) =>
    (lower !== undefined && day < startOfDay(lower)) ||
    (upper !== undefined &&
      day > new Date(upper.getFullYear(), upper.getMonth(), upper.getDate(), 23, 59, 59));

  const today = new Date();
  const selectedKey = dayKey(current);

  return (
    <div ref={root} className={styles.field}>
      <span className={styles.label}>{label}</span>
      <div className={styles.anchor}>
        <button
          type="button"
          className={styles.trigger}
          aria-label={label}
          aria-expanded={open}
          onClick={() => {
            setMonth(new Date(current.getFullYear(), current.getMonth(), 1));
            setOpen((o) => !o);
          }}
        >
          <span>
            {current.toLocaleDateString(undefined, {
              day: 'numeric',
              month: 'short',
              year: 'numeric',
            })}
            {' · '}
            {current.toLocaleTimeString(undefined, {
              hour: '2-digit',
              minute: '2-digit',
              hour12: false,
            })}
          </span>
          <Icon size={14}>
            <Calendar />
          </Icon>
        </button>
      </div>
      {open ? (
        <div
          className={styles.popover}
          data-align={align}
          role="group"
          aria-label={`${label} picker`}
        >
          <div className={styles.head}>
            <NavButton
              icon={<ChevronLeft />}
              label="Previous month"
              onClick={() => shiftMonth(-1)}
            />
            <span className={styles.month}>
              {month.toLocaleDateString(undefined, { month: 'long', year: 'numeric' })}
            </span>
            <NavButton icon={<ChevronRight />} label="Next month" onClick={() => shiftMonth(1)} />
          </div>
          <div className={styles.days}>
            {WEEKDAYS.map((d) => (
              <span key={d} className={styles.weekday}>
                {d}
              </span>
            ))}
            {gridDays(month).map((day) => {
              const selected = dayKey(day) === selectedKey;
              return (
                <button
                  type="button"
                  key={day.toISOString()}
                  className={styles.day}
                  aria-label={day.toLocaleDateString(undefined, {
                    month: 'long',
                    day: 'numeric',
                    year: 'numeric',
                  })}
                  aria-pressed={selected}
                  data-outside={day.getMonth() !== month.getMonth()}
                  data-today={dayKey(day) === dayKey(today)}
                  disabled={dayDisabled(day)}
                  onClick={() => pickDay(day)}
                >
                  {day.getDate()}
                </button>
              );
            })}
          </div>
          <div className={styles.time}>
            <span className={styles.label}>Time</span>
            <div className={styles.timeInputs}>
              <TimeInput
                label={`${label} hour`}
                value={current.getHours()}
                max={23}
                onChange={(h) => setTime(h, current.getMinutes())}
              />
              <span>:</span>
              <TimeInput
                label={`${label} minute`}
                value={current.getMinutes()}
                max={59}
                onChange={(m) => setTime(current.getHours(), m)}
              />
            </div>
          </div>
        </div>
      ) : null}
    </div>
  );
}

function NavButton({
  icon,
  label,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  onClick: () => void;
}) {
  return (
    <RoundButton size={28} aria-label={label} title={label} onClick={onClick}>
      <Icon size={14}>{icon}</Icon>
    </RoundButton>
  );
}

function TimeInput({
  label,
  value,
  max,
  onChange,
}: {
  label: string;
  value: number;
  max: number;
  onChange: (value: number) => void;
}) {
  return (
    <input
      className={styles.input}
      type="number"
      inputMode="numeric"
      aria-label={label}
      min={0}
      max={max}
      value={String(value).padStart(2, '0')}
      onChange={(event) => {
        const next = Number(event.target.value);
        if (Number.isFinite(next)) {
          onChange(Math.min(max, Math.max(0, Math.round(next))));
        }
      }}
    />
  );
}

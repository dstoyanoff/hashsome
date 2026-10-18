import { Check, ChevronDown } from 'lucide-react';
import type { EntityRef } from '@hashsome/core';
import { useId, useRef, useState } from 'react';
import { Icon } from '../icon.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { useDismiss } from '../layout/use-dismiss.ts';
import styles from './player-switcher.module.css';

/** One player the switcher can show: its ref and what to call it. */
export interface PlayerOption {
  entity: EntityRef;
  name: string;
}

/** Text with a small chevron, the name of the player being shown, which opens a short list of the others
 * to show instead. Quiet on purpose: it sits in the corner of the player, next to the artwork. */
export function PlayerSwitcher({
  players,
  current,
  onChange,
}: {
  players: PlayerOption[];

  /** The player being shown. */
  current: EntityRef;
  onChange: (entity: EntityRef) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const list = useId();
  const shown = players.find((player) => player.entity === current);

  useDismiss(open, () => setOpen(false), root);

  return (
    <div ref={root} className={styles.switcher}>
      <PlainButton
        className={styles.current}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={list}
        aria-label="Speaker"
        onClick={() => setOpen((now) => !now)}
      >
        <span className={styles.name}>{shown?.name ?? 'Speaker'}</span>
        <Icon size={14}>
          <ChevronDown />
        </Icon>
      </PlainButton>
      {open ? (
        <div id={list} className={styles.list} role="listbox" aria-label="Speakers">
          {players.map((player) => {
            const selected = player.entity === current;
            return (
              <PlainButton
                key={player.entity}
                className={styles.option}
                role="option"
                aria-selected={selected}
                onClick={() => {
                  setOpen(false);
                  if (!selected) {
                    onChange(player.entity);
                  }
                }}
              >
                <span className={styles.optionName}>{player.name}</span>
                {selected ? (
                  <Icon size={14}>
                    <Check />
                  </Icon>
                ) : null}
              </PlainButton>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}

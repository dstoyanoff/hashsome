/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import { Flex, Typography } from 'e-prim';
import { useId, useRef, useState } from 'react';
import { Icon } from '../icon.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { useDismiss } from '../layout/use-dismiss.ts';

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
    <Flex ref={root} direction="column" align="flex-start" position="relative" zIndex="dropdown">
      <PlainButton
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={list}
        aria-label="Speaker"
        onClick={() => setOpen((now) => !now)}
        align="center"
        gap={1}
        color="textMuted"
        py={1}
        maxWidth={140}
      >
        <Typography as="span" variant="label" noWrap textOverflow="ellipsis" overflow="hidden">
          {shown?.name ?? 'Speaker'}
        </Typography>
        <Icon name="lu:chevron-down" size={14} />
      </PlainButton>
      {open ? (
        <Flex
          id={list}
          role="listbox"
          aria-label="Speakers"
          direction="column"
          background="surfaceRaised"
          radius="row"
          shadow="dock"
          py={1}
          position="absolute"
          minWidth={160}
          css={{ top: '100%', left: 0 }}
        >
          {players.map((player) => {
            const selected = player.entity === current;
            return (
              <PlainButton
                key={player.entity}
                role="option"
                aria-selected={selected}
                onClick={() => {
                  setOpen(false);
                  if (!selected) {
                    onChange(player.entity);
                  }
                }}
                align="center"
                justify="space-between"
                gap={3}
                px={4}
                py={2.5}
                color={selected ? 'text' : 'textMuted'}
              >
                <Typography as="span" variant={selected ? 'bodyStrong' : 'body'} noWrap>
                  {player.name}
                </Typography>
                {selected ? <Icon name="lu:check" size={14} /> : null}
              </PlainButton>
            );
          })}
        </Flex>
      ) : null}
    </Flex>
  );
}

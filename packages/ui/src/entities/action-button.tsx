import type { EntityRef } from '@hashsome/core';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useCommand, useEntity } from '../hooks.ts';
import { Tile } from '../layout/tile.tsx';
import { entityStatus, type EntityStatus } from '../status.ts';

export interface ActionButtonProps {
  /** Button text. Defaults to the entity's name. */
  label?: string;

  /** The icon: an element such as `<Coffee />` from `lucide-react`. */
  icon?: ReactNode;

  /** A literal CSS color that fills the icon's badge, e.g. `#8B5CF6`, to tell buttons apart at a glance. Default: the usual neutral badge. */
  color?: string;

  /** A second line of text under the label. */
  secondary?: string;

  /** What to trigger: an action entity (scene, script, button — it runs) or a switch (it flips), as a ref or a handle. */
  entity?: EntityRef | EntityHandle<'action'> | EntityHandle<'switch'>;

  /** A custom press for something no entity covers (a raw backend call, your own logic). Used instead of `entity`. */
  onPress?: () => Promise<unknown>;
}

interface Pressable {
  status: EntityStatus;
  name: string | undefined;
  press: (() => Promise<unknown>) | undefined;
}

/** Normalizes an entity ref or handle to what the button needs: status, name, and how to press it. */
function usePressable(entity: ActionButtonProps['entity']): Pressable {
  const ref = typeof entity === 'string' ? entity : undefined;
  const live = useEntity(ref);
  const send = useCommand(ref);

  if (entity === undefined) {
    return { status: 'missing', name: undefined, press: undefined };
  }

  if (typeof entity === 'string') {
    const supported =
      live === undefined || live === null || live.kind === 'action' || live.kind === 'switch';

    return {
      status: supported ? entityStatus(live) : 'unsupported',
      name: live?.name,
      press: live ? () => send(live.kind === 'switch' ? 'toggle' : 'trigger') : undefined,
    };
  }

  const found = entity.entity;
  return {
    status: entity.status,
    name: found?.name,
    press: found
      ? () =>
          found.kind === 'switch'
            ? (entity as EntityHandle<'switch'>).command('toggle')
            : (entity as EntityHandle<'action'>).command('trigger')
      : undefined,
  };
}

/** Any one-shot thing to trigger — a scene, script, button or switch, or your own `onPress` — with pending / done / error feedback. */
export function ActionButton({
  label,
  icon,
  color,
  secondary,
  entity,
  onPress,
}: ActionButtonProps) {
  const target = usePressable(onPress ? undefined : entity);
  const [feedback, setFeedback] = useState<'pending' | 'done' | 'error' | undefined>();
  const [error, setError] = useState<string>();
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  const run = onPress ?? target.press;
  const press = () => {
    setFeedback('pending');
    setError(undefined);
    Promise.resolve(run ? run() : Promise.reject(new Error('Nothing to trigger')))
      .then(
        () => setFeedback('done'),
        (err: unknown) => {
          setFeedback('error');
          setError(err instanceof Error ? err.message : String(err));
        },
      )
      .finally(() => {
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setFeedback(undefined), 1200);
      });
  };

  return (
    <Tile
      label={label ?? target.name ?? fallbackName(entity)}
      {...(icon ? { icon } : {})}
      {...(color ? { iconColor: color } : {})}
      {...(secondary ? { secondary } : {})}
      status={onPress ? 'ready' : target.status}
      feedback={feedback}
      onPress={feedback === 'pending' ? undefined : press}
      {...(error ? { title: error } : {})}
    />
  );
}

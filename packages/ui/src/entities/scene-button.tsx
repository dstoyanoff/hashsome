import type { EntityRef } from '@hashsome/core';
import type { EntityHandle } from '../entity-handle.ts';
import type { IconName } from '../icon-data.ts';
import { ActionButton } from './action-button.tsx';

export interface SceneButtonProps {
  /** A scene (an `action` entity), as a ref like `ha:scene.movie_night` or a handle. */
  entity: EntityRef | EntityHandle<'action'>;

  /** Defaults to the scene's name. */
  name?: string;

  /** Icon id. Defaults to a palette. */
  icon?: IconName;

  /** A literal CSS color that fills the icon's badge, e.g. `#8B5CF6`, so scenes read as distinct at a glance (the top bar's scene dots take one too). Default: the usual neutral badge. */
  color?: string;
}

/** Activates a scene. */
export function SceneButton({ entity, name, icon, color }: SceneButtonProps) {
  return (
    <ActionButton
      entity={entity}
      {...(name ? { label: name } : {})}
      icon={icon ?? 'lu:palette'}
      {...(color ? { color } : {})}
    />
  );
}

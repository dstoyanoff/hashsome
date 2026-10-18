import type { WeatherCondition } from '@hashsome/core';
import { ICON_NODES } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { CONDITION_LOOK } from './weather-look.ts';

/** The icon for a sky condition, in its own colors: the sun yellow, a cloud blue, the rain a deeper blue. */
export function WeatherIcon({ condition, size }: { condition: WeatherCondition; size?: number }) {
  const look = CONDITION_LOOK[condition];
  const parts = ICON_NODES[look.icon]?.length ?? 0;
  const colors = Array.from({ length: parts }, (_, i) => look.parts?.[i] ?? look.color);
  return <Icon name={look.icon} colors={colors} {...(size !== undefined ? { size } : {})} />;
}

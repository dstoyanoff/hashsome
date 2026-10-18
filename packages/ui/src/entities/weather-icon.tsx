import type { WeatherCondition } from '@hashsome/core';
import type { CSSProperties } from 'react';
import { Icon } from '../icon.tsx';
import styles from './weather-icon.module.css';
import { CONDITION_LOOK } from './weather-look.tsx';

/** The icon for a sky condition, in its own colors: the sun yellow, a cloud blue, the rain a deeper blue. */
export function WeatherIcon({ condition, size }: { condition: WeatherCondition; size?: number }) {
  const look = CONDITION_LOOK[condition];
  const colors: Record<string, string> = {};
  if (look.color) {
    colors['--weather-color'] = look.color;
  }

  for (const [part, color] of Object.entries(look.parts ?? {})) {
    colors[`--weather-part-${part}`] = color;
  }

  return (
    <span className={styles.colored} style={colors as CSSProperties}>
      <Icon {...(size !== undefined ? { size } : {})}>{look.icon}</Icon>
    </span>
  );
}

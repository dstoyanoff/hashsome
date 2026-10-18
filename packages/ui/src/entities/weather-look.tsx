import {
  Cloud,
  CloudFog,
  CloudHail,
  CloudLightning,
  CloudRain,
  CloudRainWind,
  CloudSnow,
  CloudSun,
  Moon,
  Sun,
  TriangleAlert,
  Wind,
} from 'lucide-react';
import type { WeatherCondition } from '@hashsome/core';
import type { ReactNode } from 'react';

const SUN = '#FBBF24';
const CLOUD = '#60A5FA';
const RAIN = '#3B82F6';
const SNOW = '#7DD3FC';

/** What a condition looks like: an icon, and the colors it is drawn in. */
export interface ConditionLook {
  icon: ReactNode;

  /** The color of every part of the icon that `parts` does not name; the surrounding text color when left out. */
  color?: string;

  /** Colors by part of the icon (its `i`th shape, as the icon library draws them), for one drawn in two colors: the cloud blue and the sun yellow. */
  parts?: Record<number, string>;
}

export const CONDITION_LOOK: Record<WeatherCondition, ConditionLook> = {
  sunny: { icon: <Sun />, color: SUN },
  'clear-night': { icon: <Moon />, color: '#A5B4FC' },
  // The cloud is the last shape of `cloud-sun`; the rest is the sun.
  partlycloudy: { icon: <CloudSun />, color: SUN, parts: { 5: CLOUD } },
  cloudy: { icon: <Cloud />, color: CLOUD },
  fog: { icon: <CloudFog />, parts: { 0: CLOUD } },
  rainy: { icon: <CloudRain />, color: RAIN, parts: { 0: CLOUD } },
  pouring: { icon: <CloudRainWind />, color: RAIN, parts: { 0: CLOUD } },
  snowy: { icon: <CloudSnow />, color: SNOW, parts: { 0: CLOUD } },
  'snowy-rainy': { icon: <CloudSnow />, color: RAIN, parts: { 0: CLOUD } },
  hail: { icon: <CloudHail />, color: SNOW, parts: { 0: CLOUD } },
  lightning: { icon: <CloudLightning />, color: SUN, parts: { 0: CLOUD } },
  'lightning-rainy': { icon: <CloudLightning />, color: SUN, parts: { 0: CLOUD } },
  windy: { icon: <Wind /> },
  exceptional: { icon: <TriangleAlert />, color: '#F2554A' },
  unknown: { icon: <Cloud /> },
};

/** A condition in words. */
export const CONDITION_LABEL: Record<WeatherCondition, string> = {
  sunny: 'Sunny',
  'clear-night': 'Clear',
  partlycloudy: 'Partly cloudy',
  cloudy: 'Cloudy',
  fog: 'Fog',
  rainy: 'Rain',
  pouring: 'Heavy rain',
  snowy: 'Snow',
  'snowy-rainy': 'Sleet',
  hail: 'Hail',
  lightning: 'Thunderstorm',
  'lightning-rainy': 'Thunderstorm with rain',
  windy: 'Windy',
  exceptional: 'Exceptional weather',
  unknown: 'Unknown',
};

const COMPASS = [
  'N',
  'NNE',
  'NE',
  'ENE',
  'E',
  'ESE',
  'SE',
  'SSE',
  'S',
  'SSW',
  'SW',
  'WSW',
  'W',
  'WNW',
  'NW',
  'NNW',
];

/** The compass point for a bearing in degrees: `225` is `SW`. */
export function compass(degrees: number): string {
  return COMPASS[Math.round((((degrees % 360) + 360) % 360) / 22.5) % 16]!;
}

/** How strong a UV index is, in words. */
export function uvLevel(index: number): string {
  if (index < 3) {
    return 'low';
  }

  if (index < 6) {
    return 'moderate';
  }

  if (index < 8) {
    return 'high';
  }

  return index < 11 ? 'very high' : 'extreme';
}

import { useTheme } from '@emotion/react';
import type { TypographyKey } from '../style/prim.tsx';

/** A typography variant's resolved font size, for the rare consumer that can't render a
 * `<Typography>` — e.g. an SVG chart's axis ticks. Everything else should use `<Typography>`. */
export function useTypographySize(key: Exclude<TypographyKey, 'default'>): number {
  const { typography } = useTheme();
  const specs = typography as unknown as Record<string, { fontSize?: unknown } | undefined>;
  const size = specs[key]?.fontSize ?? typography.default.fontSize;
  return typeof size === 'number' ? size : 16;
}

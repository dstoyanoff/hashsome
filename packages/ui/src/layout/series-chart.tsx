/** @jsxImportSource @emotion/react */
import { lazy, Suspense } from 'react';
import { Box, Flex, Typography } from '../style/prim.tsx';

export interface SeriesSample {
  /** ISO 8601. */
  timestamp: string;
  value: number;

  /** Extra lines the tooltip shows under the value, e.g. the sky and the wind at that hour. */
  notes?: string[];
}

/** The window a chart covers; `custom` is a from/to pair the caller has already applied to the samples. */
export type SeriesRange = '1h' | '1d' | '1w' | '1m' | 'custom';

export function formatTick(range: SeriesRange, iso: string): string {
  const date = new Date(iso);
  if (range === '1h' || range === '1d') {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }

  if (range === '1w') {
    return date.toLocaleDateString([], { weekday: 'short' });
  }

  return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

export interface SeriesChartProps {
  samples: SeriesSample[];
  range: SeriesRange;
  expanded: boolean;
  unit: string;

  /** Fit the y axis to the data instead of starting at 0 — right for temperature/humidity, wrong for power. */
  fitDomain?: boolean;

  /** The chart's height in px. Default 48 for the sparkline, 220 when expanded. */
  height?: number;
}

const SeriesPlot = lazy(() => import('./series-plot.tsx'));

/** A bare sparkline, or — when `expanded` — the full chart with axes, grid and a tooltip. Shared by the power and sensor drawers. */
export function SeriesChart(props: SeriesChartProps) {
  // The room the chart will take is there meanwhile, so nothing moves when it arrives.
  return (
    <Suspense fallback={<Box height={props.height ?? (props.expanded ? 220 : 48)} mt={3} />}>
      <SeriesPlot {...props} />
    </Suspense>
  );
}

export function RangeSwitcher<T extends string>({
  range,
  options,
  onChange,
}: {
  range: T;
  options: { value: T; label: string }[];
  onChange: (range: T) => void;
}) {
  return (
    <Flex background="surfaceRaised" radius="full" p={0.5} gap={0.5}>
      {options.map((option) => {
        const active = option.value === range;
        return (
          <Flex
            as="button"
            key={option.value}
            type="button"
            onClick={() => onChange(option.value)}
            grow={1}
            radius="full"
            cursor="pointer"
            align="center"
            justify="center"
            py={1.25}
            px={2.5}
            background={active ? 'accent' : 'transparent'}
            color={active ? 'accentText' : 'text'}
          >
            <Typography as="span" variant="label">
              {option.label}
            </Typography>
          </Flex>
        );
      })}
    </Flex>
  );
}

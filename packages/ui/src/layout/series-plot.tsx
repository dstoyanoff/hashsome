/** @jsxImportSource @emotion/react */
import type { ComponentProps } from 'react';
import { Box, Flex, Typography, useColorByKey } from 'e-prim';
import { useTypographySize } from '../theme/use-typography.ts';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
  type TooltipContentProps,
} from 'recharts';
import { formatTick, type SeriesChartProps, type SeriesSample } from './series-chart.tsx';

// The chart itself, apart from `series-chart.tsx` so that the charting library is a file of its own, fetched when a
// chart is first drawn: most pages never draw one, and it is a good part of what a display has to load and keep.

/** The grid's dashed lines, but not one along the chart's bottom edge: that is the solid axis line. */
const dashedExceptBottom =
  (stroke: string): NonNullable<ComponentProps<typeof CartesianGrid>['horizontal']> =>
  ({
    x1,
    x2,
    y1,
    offset,
  }: {
    x1: number;
    x2: number;
    y1: number;
    offset: { top: number; height: number };
  }) =>
    y1 >= offset.top + offset.height - 1 ? (
      <g />
    ) : (
      <line
        x1={x1}
        x2={x2}
        y1={y1}
        y2={y1}
        stroke={stroke}
        strokeOpacity={0.3}
        strokeDasharray="3 3"
      />
    );

/** The chart `SeriesChart` draws once this has been loaded. */
export default function SeriesPlot({
  samples,
  range,
  expanded,
  unit,
  fitDomain = false,
  height,
}: SeriesChartProps) {
  const accent = useColorByKey('accent') ?? '';
  const textMuted = useColorByKey('textMuted') ?? '';
  const line = useColorByKey('line') ?? '';
  const tickSize = useTypographySize('secondary');

  return (
    <Box height={height ?? (expanded ? 220 : 48)} mt={3}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={samples} margin={{ top: 4, right: 0, bottom: 0, left: 0 }}>
          {expanded ? (
            <CartesianGrid
              stroke={line}
              strokeOpacity={0.3}
              strokeDasharray="3 3"
              vertical={false}
              // The chart's bottom edge is the solid axis line, so no dashed line goes under it.
              horizontal={dashedExceptBottom(line)}
            />
          ) : null}
          {expanded ? (
            <XAxis
              dataKey="timestamp"
              tickFormatter={(value: string) => formatTick(range, value)}
              tick={{ fontSize: tickSize, fill: textMuted }}
              // The bottom of the chart is a solid line; the grid above it is dashed and lighter.
              axisLine={{ stroke: line, strokeOpacity: 0.5, strokeWidth: 0.5 }}
              tickLine={false}
              minTickGap={24}
            />
          ) : null}
          {expanded ? (
            <YAxis
              width={36}
              {...(fitDomain ? { domain: ['auto', 'auto'] as [string, string] } : {})}
              tick={{ fontSize: tickSize, fill: textMuted }}
              axisLine={false}
              tickLine={false}
            />
          ) : null}
          <Tooltip
            content={<ChartTooltip unit={unit} />}
            // The collapsed sparkline is too short to hold the tooltip, so float it above.
            {...(expanded
              ? {}
              : { allowEscapeViewBox: { x: false, y: true }, position: { y: -52 } })}
          />
          <Area
            type="monotone"
            dataKey="value"
            stroke={accent}
            fill={accent}
            fillOpacity={0.2}
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </Box>
  );
}

function ChartTooltip({
  active,
  payload,
  unit,
}: Partial<TooltipContentProps<number, string>> & { unit: string }) {
  if (!active || !payload?.length) {
    return null;
  }

  const sample = payload[0]?.payload as SeriesSample | undefined;
  if (!sample) {
    return null;
  }

  return (
    <Box
      background="surfaceRaised"
      color="text"
      border
      radius="small"
      typography="body"
      py={1.5}
      px={2.5}
    >
      <Flex direction="column">
        <Typography as="span" variant="secondary" color="textMuted">
          {new Date(sample.timestamp).toLocaleString([], {
            month: 'short',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
          })}
        </Typography>
        <Typography as="span" variant="bodyStrong">
          {Math.round(sample.value * 10) / 10} {unit}
        </Typography>
        {sample.notes?.map((note) => (
          <Typography key={note} as="span" variant="secondary" color="textMuted">
            {note}
          </Typography>
        ))}
      </Flex>
    </Box>
  );
}

/** @jsxImportSource @emotion/react */
import { Flex, Typography } from '../style/prim.tsx';
import type { EntityRef } from '@hashsome/core';
import { useState } from 'react';
import { useEntityHistory } from '../use-entity-history.ts';
import { DateTimeField } from './date-time-field.tsx';
import { useDetail } from './detail-provider.tsx';
import {
  aggregate,
  downsample,
  fromDateTimeInput,
  periodStart,
  samplesBetween,
  toDateTimeInput,
  type MinMax,
} from './sensor-stats.ts';
import {
  RangeSwitcher,
  SeriesChart,
  type SeriesRange,
  type SeriesSample,
} from './series-chart.tsx';

export type { SeriesSample as SensorSample } from './series-chart.tsx';

const DAY = 24 * 60 * 60_000;

const RANGES: { value: SeriesRange; label: string }[] = [
  { value: '1h', label: '1H' },
  { value: '1d', label: '1D' },
  { value: '1w', label: '1W' },
  { value: '1m', label: '1M' },
  { value: 'custom', label: 'Custom' },
];

/** The month's hourly points up to where the week's finer points begin, then those. */
function mergeHistory(
  coarse: SeriesSample[] | undefined,
  fine: SeriesSample[] | undefined,
): SeriesSample[] {
  const from = fine?.[0]?.timestamp;
  return [
    ...(coarse ?? []).filter((sample) => from === undefined || sample.timestamp < from),
    ...(fine ?? []),
  ];
}

const WINDOW_MS: Record<Exclude<SeriesRange, 'custom'>, number> = {
  '1h': 60 * 60_000,
  '1d': DAY,
  '1w': 7 * DAY,
  '1m': 30 * DAY,
};

function formatNumber(value: number): string {
  return String(Math.round(value * 10) / 10);
}

/**
 * A sensor's history for its detail drawer: a bare sparkline while the drawer is at its normal
 * width, and — once expanded — the full chart with a range switcher (including a custom from/to
 * date and time range). Min / max tiles aggregate the supplied samples: today / this week / this month
 * collapsed, today / last 7 / last 30 days expanded, plus the custom range when one is chosen.
 */
export function SensorHistory({
  samples: given,
  entity,
  unit,
}: {
  /** Readings over time, oldest first; ideally covering at least the last 30 days. Left out, they come from `entity`'s history when the backend keeps one. */
  samples?: SeriesSample[];
  entity?: EntityRef;
  unit: string;
}) {
  // Hourly averages for the month, with five-minute ones for the last week laid over them, so the
  // 1H and 1D views have more than a point or two.
  const month = useEntityHistory(given ? undefined : entity, { range: '1m', bucket: '1h' });
  const week = useEntityHistory(given ? undefined : entity, { range: '1w', bucket: '5m' });
  const samples = given ?? mergeHistory(month.result?.points, week.result?.points);
  const { detail } = useDetail();
  const expanded = detail?.expanded ?? false;
  const now = new Date();
  const [range, setRange] = useState<SeriesRange>('1d');
  const [from, setFrom] = useState(() => toDateTimeInput(new Date(now.getTime() - 7 * DAY)));
  const [to, setTo] = useState(() => toDateTimeInput(now));

  if (samples.length === 0) {
    return null;
  }

  const fromDate = fromDateTimeInput(from);
  const toDate = fromDateTimeInput(to);
  const customWindow =
    fromDate && toDate && fromDate <= toDate ? { from: fromDate, to: toDate } : undefined;

  // Collapsed always shows the 1D sparkline; the switcher only exists once expanded.
  const activeRange: SeriesRange = expanded ? range : '1d';
  const chartSamples =
    activeRange === 'custom'
      ? customWindow
        ? samplesBetween(samples, customWindow.from, customWindow.to)
        : []
      : samplesBetween(samples, new Date(now.getTime() - WINDOW_MS[activeRange]), now);

  const stat = (start: Date): MinMax | undefined => aggregate(samplesBetween(samples, start, now));
  const tiles: { label: string; value: MinMax | undefined }[] = expanded
    ? [
        { label: 'Today', value: stat(periodStart('today', now)) },
        { label: 'Last 7 days', value: stat(periodStart('last7d', now)) },
        { label: 'Last 30 days', value: stat(periodStart('last30d', now)) },
        ...(range === 'custom'
          ? [
              {
                label: 'Custom range',
                value: customWindow
                  ? aggregate(samplesBetween(samples, customWindow.from, customWindow.to))
                  : undefined,
              },
            ]
          : []),
      ]
    : [
        { label: 'Today', value: stat(periodStart('today', now)) },
        { label: 'This week', value: stat(periodStart('week', now)) },
        { label: 'This month', value: stat(periodStart('month', now)) },
      ];

  return (
    <Flex direction="column" gap={2} mt={4}>
      <Flex justify="space-between" align="center">
        <Typography as="span" variant="eyebrow" uppercase color="textMuted">
          History
        </Typography>
        {expanded ? <RangeSwitcher range={range} options={RANGES} onChange={setRange} /> : null}
      </Flex>
      {expanded && range === 'custom' ? (
        <Flex align="center" gap={4} css={{ flexWrap: 'wrap' }}>
          <DateTimeField label="From" value={from} max={to} onChange={setFrom} />
          <DateTimeField label="To" value={to} min={from} align="right" onChange={setTo} />
        </Flex>
      ) : null}
      <Flex gap={2}>
        {tiles.map((tile) => (
          <Flex
            key={tile.label}
            direction="column"
            grow={1}
            minWidth={0}
            background="surfaceRaised"
            radius="row"
            py={2.5}
            px={3}
            css={{ flexBasis: 0 }}
          >
            <Typography as="span" variant="secondary" color="textMuted">
              {tile.label}
            </Typography>
            <Typography as="span" variant="bodyStrong" noWrap>
              {tile.value
                ? `${formatNumber(tile.value.min)} – ${formatNumber(tile.value.max)} ${unit}`
                : '—'}
            </Typography>
          </Flex>
        ))}
      </Flex>
      {chartSamples.length === 0 ? (
        <Typography as="span" variant="secondary" color="textMuted">
          No readings in this range.
        </Typography>
      ) : (
        <SeriesChart
          samples={downsample(chartSamples, 240)}
          range={activeRange}
          expanded={expanded}
          unit={unit}
          fitDomain
        />
      )}
    </Flex>
  );
}

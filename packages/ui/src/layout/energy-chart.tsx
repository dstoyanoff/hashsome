/** @jsxImportSource @emotion/react */
import type { EntityRef } from '@hashsome/core';
import { Flex, Typography } from 'e-prim';
import { useState } from 'react';
import { useEntityHandle } from '../hooks.ts';
import { useEntityHistory } from '../use-entity-history.ts';
import { usageFromDaily } from './energy-usage.ts';
import { AnimatedNumber, decimalsOf } from './animated-number.tsx';
import { useDetail } from './detail-provider.tsx';
import { RangeSwitcher, SeriesChart } from './series-chart.tsx';

export interface EnergySample {
  /** ISO 8601. */
  timestamp: string;
  watts: number;
}

export type EnergyRange = '1h' | '1d' | '1w' | '1m';

const ENERGY_RANGES: { value: EnergyRange; label: string }[] = [
  { value: '1h', label: '1H' },
  { value: '1d', label: '1D' },
  { value: '1w', label: '1W' },
  { value: '1m', label: '1M' },
];

/** Energy used (kWh) per period. Collapsed shows today / this week / this month; expanded swaps the last two for the rolling last 7 / last 30 days. */
export interface EnergyUsage {
  today?: number;
  week?: number;
  month?: number;
  last7d?: number;
  last30d?: number;
}

export interface EnergyChartProps {
  /** Live power sensor (current draw, e.g. a Shelly's `sensor.*_power`). Its reading is the headline and updates as it changes; without it the headline falls back to the last chart sample. */
  power?: EntityRef;

  /** Lifetime energy counter (kWh, e.g. `sensor.*_energy`). Shown as a quiet "Lifetime" line when expanded — per-period usage can't be derived from a counter without history, so pass `usage` for that. */
  energy?: EntityRef;

  /** Usage per period in kWh. Left out, it is worked out from `energy`'s history when the backend keeps one (Home Assistant's long-term statistics); pass it to use your own. */
  usage?: EnergyUsage;

  /** Samples for each range the person can pick; `onRangeChange` fires on pick. Left out, they are loaded from `power`'s history when the backend keeps one, for the selected range; pass them to use your own. */
  seriesByRange?: Partial<Record<EnergyRange, EnergySample[]>>;

  /** Called when the person picks another range, so a real data source can load it. */
  onRangeChange?: (range: EnergyRange) => void;

  /** Default `'W'`. */
  unit?: string;
}

function formatEnergy(kwh: number): string {
  if (kwh < 1) {
    return `${Math.round(kwh * 1000)} Wh`;
  }

  return `${kwh < 10 ? kwh.toFixed(2) : kwh.toFixed(1)} kWh`;
}

/**
 * A power-draw chart for an entity's detail drawer, e.g. from a Shelly's energy monitoring.
 * Generic — any entity type can supply it. Deliberately two-tier: a bare sparkline while the
 * drawer is at its normal (side) width, and the full interactive chart — axes, tooltip, a
 * selectable time range — only once it's expanded to fullscreen, where there's room for it.
 */
export function EnergyChart({
  power,
  energy,
  usage,
  seriesByRange = {},
  onRangeChange,
  unit = 'W',
}: EnergyChartProps) {
  const { detail } = useDetail();
  const expanded = detail?.expanded ?? false;
  const [range, setRange] = useState<EnergyRange>('1d');

  // Sparkline (collapsed) and chart (expanded) both show the selected range, 1D until picked.
  const ownSamples = seriesByRange[range];
  const powerHistory = useEntityHistory(
    ownSamples ? undefined : power,
    power ? { range } : undefined,
  );

  const energyHistory = useEntityHistory(
    usage ? undefined : energy,
    energy ? { range: '1m', bucket: '1d' } : undefined,
  );

  const samples =
    ownSamples ??
    (powerHistory.result?.points ?? []).map((point) => ({
      timestamp: point.timestamp,
      watts: point.value,
    }));

  const shownUsage =
    usage ??
    (energyHistory.result ? usageFromDaily(energyHistory.result.points, new Date()) : undefined);

  if (!power && !shownUsage && !energy && samples.length === 0) {
    return null;
  }

  const fallbackCurrent = samples.at(-1)?.watts ?? 0;

  const selectRange = (next: EnergyRange) => {
    setRange(next);
    onRangeChange?.(next);
  };

  return (
    <Flex direction="column" gap={2} mt={4}>
      <Flex justify="space-between" align="center">
        <Typography as="span" variant="eyebrow" uppercase color="textMuted">
          Power
        </Typography>
        {expanded ? (
          <RangeSwitcher range={range} options={ENERGY_RANGES} onChange={selectRange} />
        ) : null}
      </Flex>
      {power ? (
        <PowerReading entity={power} fallbackUnit={unit} />
      ) : (
        <Typography as="span" variant="stat">
          {fallbackCurrent} {unit}
        </Typography>
      )}
      {shownUsage ? <UsageTiles usage={shownUsage} expanded={expanded} /> : null}
      {expanded && energy ? <LifetimeEnergy entity={energy} /> : null}
      {samples.length === 0 ? null : (
        <SeriesChart
          samples={samples.map((sample) => ({ timestamp: sample.timestamp, value: sample.watts }))}
          range={range}
          expanded={expanded}
          unit={unit}
        />
      )}
    </Flex>
  );
}

function PowerReading({ entity, fallbackUnit }: { entity: EntityRef; fallbackUnit: string }) {
  const handle = useEntityHandle('sensor', entity);
  const sensor = handle.entity;
  const unit = sensor?.unit ?? fallbackUnit;
  return (
    <Typography as="span" variant="stat" aria-live="off">
      {handle.status === 'ready' && sensor?.numeric !== undefined ? (
        <AnimatedNumber
          value={sensor.numeric}
          format={(n) => `${n.toFixed(decimalsOf(sensor.numeric ?? 0))} ${unit}`}
        />
      ) : (
        '—'
      )}
    </Typography>
  );
}

function LifetimeEnergy({ entity }: { entity: EntityRef }) {
  const handle = useEntityHandle('sensor', entity);
  const sensor = handle.entity;
  if (handle.status !== 'ready' || sensor?.numeric === undefined) {
    return null;
  }

  return (
    <Typography as="span" variant="secondary" color="textMuted">
      Lifetime {sensor.numeric} {sensor.unit ?? 'kWh'}
    </Typography>
  );
}

function UsageTiles({ usage, expanded }: { usage: EnergyUsage; expanded: boolean }) {
  const items: { label: string; value: number | undefined }[] = expanded
    ? [
        { label: 'Today', value: usage.today },
        { label: 'Last 7 days', value: usage.last7d },
        { label: 'Last 30 days', value: usage.last30d },
      ]
    : [
        { label: 'Today', value: usage.today },
        { label: 'This week', value: usage.week },
        { label: 'This month', value: usage.month },
      ];

  const shown = items.filter((item) => item.value !== undefined);
  if (shown.length === 0) {
    return null;
  }

  return (
    <Flex gap={2}>
      {shown.map((item) => (
        <Flex
          key={item.label}
          direction="column"
          grow={1}
          background="surfaceRaised"
          radius="row"
          py={2.5}
          px={3}
          css={{ flexBasis: 0 }}
        >
          <Typography as="span" variant="secondary" color="textMuted">
            {item.label}
          </Typography>
          <Typography as="span" variant="bodyStrong">
            {formatEnergy(item.value ?? 0)}
          </Typography>
        </Flex>
      ))}
    </Flex>
  );
}

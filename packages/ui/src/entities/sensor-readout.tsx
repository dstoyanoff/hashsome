import type { ReactNode } from 'react';
import {
  Battery,
  BatteryFull,
  BatteryLow,
  BatteryMedium,
  Droplet,
  Gauge,
  Thermometer,
  TriangleAlert,
} from 'lucide-react';
import type { EntityRef } from '@hashsome/core';
import { Icon } from '../icon.tsx';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { AnimatedNumber } from '../layout/animated-number.tsx';
import type { SensorSample } from '../layout/sensor-history.tsx';
import { lazyExport } from '../layout/lazy.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { DrawerTrigger } from '../layout/use-drawer.tsx';
import { capitalize, statusLabels } from '../status.ts';
import styles from './sensor-readout.module.css';

const SensorHistory = lazyExport<{ samples?: SensorSample[]; entity?: EntityRef; unit: string }>(
  () => import('../layout/sensor-history.tsx'),
  'SensorHistory',
);

/** Bounds a reading is considered safe within; either end can be left open. */
export interface SafeRange {
  min?: number;
  max?: number;
}

export interface SensorReadoutProps {
  /** A sensor, as a ref like `ha:sensor.living_room_humidity` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'sensor'>;

  /** Icon id. Defaults by device class (temperature, humidity). */
  icon?: ReactNode;

  /** Drawer title. Defaults to the sensor's name. */
  name?: string;

  /** The sensor's battery level, as a sensor ref like `ha:sensor.kitchen_t_h_sensor_battery` (a percentage). Shown in the drawer, in red when low. */
  battery?: EntityRef;

  /** Readings over time (oldest first). Adds a chart and min/max for today / this week / this month to the drawer, plus a custom date and time range when expanded. Left out, they come from the backend's own history when it keeps one (Home Assistant's long-term statistics). */
  history?: SensorSample[];

  /** Flags readings outside `{ min, max }` with a warning. Humidity defaults to 30–60 %; pass `false` to turn a default off. */
  safeRange?: SafeRange | false;

  /** Tapping a ready numeric reading opens a drawer (current value and safe-range verdict, plus the chart and min/max when `history` is given). Default `true`; `false` keeps it inline text, e.g. for a presence chip. */
  drawer?: boolean;
}

const deviceClassIcons: Record<string, ReactNode> = {
  temperature: <Thermometer />,
  humidity: <Droplet />,
};

const DEFAULT_SAFE_RANGES: Record<string, SafeRange> = {
  humidity: { min: 30, max: 60 },
};

type RangeLevel = 'low' | 'high' | 'ok';

function formatValue(raw: string): string {
  const value = Number(raw);
  return raw.trim() !== '' && Number.isFinite(value) ? String(Math.round(value * 10) / 10) : raw;
}

function rangeLevel(raw: string | undefined, range: SafeRange | undefined): RangeLevel | undefined {
  const value = Number(raw);
  if (!range || raw === undefined || raw.trim() === '' || !Number.isFinite(value)) {
    return undefined;
  }

  if (range.min !== undefined && value < range.min) {
    return 'low';
  }

  if (range.max !== undefined && value > range.max) {
    return 'high';
  }

  return 'ok';
}

function describeRange(range: SafeRange, unit: string): string {
  const suffix = unit ? ` ${unit}` : '';
  if (range.min !== undefined && range.max !== undefined) {
    return `${range.min}–${range.max}${suffix}`;
  }

  return range.min !== undefined
    ? `at least ${range.min}${suffix}`
    : `at most ${range.max}${suffix}`;
}

/** Inline value with icon and unit. Shows "Unavailable" / "Unknown" instead of a reading, and a
 * warning when the value leaves its safe range. Tapping a numeric reading opens a drawer. */
export function SensorReadout({
  entity,
  icon,
  name,
  battery,
  history,
  safeRange,
  drawer = true,
}: SensorReadoutProps) {
  const handle = useEntityHandle('sensor', entity);
  const { status } = handle;
  const sensor = handle.entity;
  const ready = status === 'ready' && sensor !== undefined;
  const deviceClass = sensor?.measurement;
  const unit = sensor?.unit ?? '';
  const resolvedIcon: ReactNode = icon ??
    (deviceClass ? deviceClassIcons[deviceClass] : undefined) ?? <Gauge />;

  const range =
    safeRange === false
      ? undefined
      : (safeRange ?? (deviceClass ? DEFAULT_SAFE_RANGES[deviceClass] : undefined));

  const level = ready ? rangeLevel(sensor.value, range) : undefined;
  const label = name ?? sensor?.name ?? fallbackName(entity);
  const valueText = ready ? `${formatValue(sensor.value)}${unit ? ` ${unit}` : ''}` : '';
  // A number that changes moves to its new value; written to one decimal, as `valueText` is.
  const reading = (n: number) => `${formatValue(String(n))}${unit ? ` ${unit}` : ''}`;
  const moving =
    ready && sensor.numeric !== undefined ? Math.round(sensor.numeric * 10) / 10 : undefined;

  const warning = level === 'low' || level === 'high';
  const hint =
    warning && range
      ? `${label} is too ${level} (safe range ${describeRange(range, unit)})`
      : undefined;

  const content = (
    <>
      <Icon size={16}>{resolvedIcon}</Icon>
      <span className={styles.value}>
        {ready ? (
          moving !== undefined ? (
            <AnimatedNumber value={moving} format={reading} />
          ) : (
            valueText
          )
        ) : (
          statusLabels[status as Exclude<typeof status, 'ready'>]
        )}
      </span>
      {warning ? (
        <Icon size={16}>
          <TriangleAlert />
        </Icon>
      ) : null}
    </>
  );

  const numeric = ready && sensor.numeric !== undefined;
  if (drawer && numeric) {
    return (
      <DrawerTrigger
        icon={resolvedIcon}
        label={label}
        kind={deviceClass ? capitalize(deviceClass) : 'Sensor'}
        body={
          <SensorDetailBody
            valueText={valueText}
            {...(moving !== undefined ? { moving, reading } : {})}
            level={level}
            range={range}
            unit={unit}
            {...(battery ? { battery } : {})}
            {...(typeof entity === 'string' ? { entity } : {})}
            {...(history ? { history } : {})}
          />
        }
      >
        {(open) => (
          <PlainButton
            className={styles.readout}
            data-status={status}
            data-range={level}
            aria-label={`${label}: ${valueText}${hint ? `. ${hint}` : ''}`}
            title={hint}
            onClick={open}
          >
            {content}
          </PlainButton>
        )}
      </DrawerTrigger>
    );
  }

  return (
    <span className={styles.readout} data-status={status} data-range={level} title={hint}>
      {content}
    </span>
  );
}

function SensorDetailBody({
  valueText,
  moving,
  reading,
  level,
  range,
  unit,
  entity,
  battery,
  history,
}: {
  valueText: string;

  /** The reading as a number, and how it is written, when it is one: it moves when it changes. */
  moving?: number;
  reading?: (n: number) => string;
  level: RangeLevel | undefined;
  range: SafeRange | undefined;
  unit: string;
  entity?: EntityRef;
  battery?: EntityRef;
  history?: SensorSample[];
}) {
  const warning = level === 'low' || level === 'high';
  return (
    <div className={styles.body}>
      <div className={styles.now}>
        <span className={styles.nowLabel}>Now</span>
        <span className={styles.nowValue}>
          {moving !== undefined && reading ? (
            <AnimatedNumber value={moving} format={reading} />
          ) : (
            valueText
          )}
        </span>
        {range ? (
          <div className={styles.verdict} data-warning={warning}>
            {warning ? (
              <Icon size={14}>
                <TriangleAlert />
              </Icon>
            ) : null}
            <span className={styles.verdictText}>
              {warning
                ? `Too ${level} · safe range ${describeRange(range, unit)}`
                : `Within the safe range (${describeRange(range, unit)})`}
            </span>
          </div>
        ) : null}
      </div>
      {battery ? <BatteryLevel entity={battery} /> : null}
      <SensorHistory {...(history ? { samples: history } : entity ? { entity } : {})} unit={unit} />
    </div>
  );
}

/** Below this a battery is shown as low. */
const LOW_BATTERY = 20;

const batteryIcon = (percent: number): ReactNode =>
  percent <= LOW_BATTERY ? <BatteryLow /> : percent <= 60 ? <BatteryMedium /> : <BatteryFull />;

/** A sensor's battery, in its drawer: the level, red and with a warning once it is low. */
function BatteryLevel({ entity }: { entity: EntityRef }) {
  const handle = useEntityHandle('sensor', entity);
  const level = handle.status === 'ready' ? handle.entity?.numeric : undefined;
  const low = level !== undefined && level <= LOW_BATTERY;
  return (
    <div className={styles.battery} data-low={low}>
      <Icon size={16}>{level === undefined ? <Battery /> : batteryIcon(level)}</Icon>
      <span className={styles.batteryLabel}>{low ? 'Battery low' : 'Battery'}</span>
      <span className={styles.batteryLevel}>
        {level === undefined ? '—' : `${Math.round(level)}%`}
      </span>
    </div>
  );
}

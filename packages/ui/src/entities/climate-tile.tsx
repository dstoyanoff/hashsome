import {
  Droplets,
  Flame,
  Minus,
  Plus,
  Power,
  RefreshCw,
  Snowflake,
  Thermometer,
  ThermometerSun,
  Wind,
  X,
} from 'lucide-react';
import type { ClimateEntity, ClimateMode, EntityRef } from '@hashsome/core';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { AnimatedNumber, decimalsOf } from '../layout/animated-number.tsx';
import { ChipRow, StepButton } from '../layout/drawer-controls.tsx';
import type { EnergyChartProps } from '../layout/energy-chart.tsx';
import type { LogbookEntry } from '../layout/history-section.tsx';
import { Stepper, StepperValue } from '../layout/stepper.tsx';
import { SwatchRow } from '../layout/swatch-row.tsx';
import { IconButton, Tile } from '../layout/tile.tsx';
import rows from '../style/rows.module.css';

export interface ClimateTileProps {
  /** A thermostat or heater, as a ref like `ha:climate.living_room` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'climate'>;

  /** Defaults to the device's name. */
  name?: string;

  /** The icon, an element such as `<Thermometer />`. Defaults to a thermometer. */
  icon?: ReactNode;

  /** Power-draw chart for the drawer, above `history`. No default source yet — pass explicitly. */
  energy?: EnergyChartProps;

  /** Recent activity, shown in the drawer: who or what changed it, and when. Left out, it comes from the backend's own record when it keeps one (Home Assistant's logbook), fetched when the person opens its History. */
  history?: LogbookEntry[];
}

interface ModeMeta {
  icon: ReactNode;
  color: string;
  label: string;
}

const MODE_META: Record<ClimateMode, ModeMeta> = {
  off: { icon: <Power />, color: '#6b6b73', label: 'Off' },
  heat: { icon: <Flame />, color: '#ff7a45', label: 'Heat' },
  cool: { icon: <Snowflake />, color: '#4dc9ff', label: 'Cool' },
  auto: { icon: <RefreshCw />, color: '#5fd17a', label: 'Auto' },
  heatCool: { icon: <ThermometerSun />, color: '#b36bff', label: 'Heat/Cool' },
  dry: { icon: <Droplets />, color: '#fbbf24', label: 'Dry' },
  fanOnly: { icon: <Wind />, color: '#2dd4bf', label: 'Fan' },
};

const FALLBACK_MODES: ClimateMode[] = ['off', 'heat'];

function modeMeta(mode: ClimateMode): ModeMeta {
  return MODE_META[mode];
}

function title(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1).replace(/_/g, ' ');
}

/** Heater / thermostat: a mode button that opens mode swatches in the card, a target temperature
 * stepper, and (on hold) a drawer with modes, a target bar, presets, energy and history. */
export function ClimateTile({ entity, name, icon, energy, history }: ClimateTileProps) {
  const handle = useEntityHandle('climate', entity);
  const [picking, setPicking] = useState(false);

  const { status } = handle;
  const climate = handle.entity;
  const mode = climate?.mode ?? 'off';
  const target = climate?.targetTemperature;
  const current = climate?.currentTemperature;
  const step = climate?.capabilities.step ?? 0.5;
  const min = climate?.capabilities.range.min ?? 5;
  const max = climate?.capabilities.range.max ?? 35;
  const unit = climate?.unit ?? '°C';
  const reported = climate?.capabilities.modes ?? [];
  const modes = reported.length > 0 ? reported : FALLBACK_MODES;
  const ready = status === 'ready';

  const setTarget = (value: number) =>
    void handle.command('setTargetTemperature', {
      temperature: Math.min(max, Math.max(min, Math.round(value * 100) / 100)),
    });

  const setMode = (next: ClimateMode) => void handle.command('setMode', { mode: next });

  return (
    <Tile
      label={name ?? climate?.name ?? fallbackName(entity)}
      icon={icon ?? <Thermometer />}
      kind="Climate"
      status={status}
      secondary={
        current !== undefined ? (
          <AnimatedNumber
            value={current}
            format={(n) => `${n.toFixed(decimalsOf(current))} ${unit}`}
          />
        ) : undefined
      }
      detail={
        <ClimateDetailBody
          climate={climate}
          mode={mode}
          modes={modes}
          target={target}
          current={current}
          step={step}
          min={min}
          max={max}
          unit={unit}
          onMode={setMode}
          onTarget={setTarget}
          onPreset={(preset) => void handle.command('setPreset', { preset })}
        />
      }
      {...(energy ? { energy } : {})}
      {...(history ? { history } : {})}
      {...(typeof entity === 'string' ? { logbook: entity } : {})}
      {...(ready && picking
        ? {
            // A render prop `Tile` calls, not a component of its own.
            // oxlint-disable-next-line react/no-unstable-nested-components
            overlay: () => (
              <SwatchRow
                items={modes.map((m) => ({
                  key: m,
                  label: modeMeta(m).label,
                  selected: m === mode,
                  color: modeMeta(m).color,
                  icon: modeMeta(m).icon,
                  onPick: () => {
                    setMode(m);
                    setPicking(false);
                  },
                }))}
              />
            ),
          }
        : {})}
      trailing={
        ready ? (
          <>
            <IconButton
              icon={picking ? <X /> : modeMeta(mode).icon}
              label={picking ? 'Close modes' : 'Mode'}
              active={!picking && mode !== 'off'}
              onClick={() => setPicking((open) => !open)}
              size={16}
            />
            {target !== undefined ? (
              <Stepper>
                <IconButton
                  icon={<Minus />}
                  label="Decrease"
                  disabled={target <= min}
                  onClick={() => setTarget(target - step)}
                />
                <StepperValue>
                  {target} {unit}
                </StepperValue>
                <IconButton
                  icon={<Plus />}
                  label="Increase"
                  disabled={target >= max}
                  onClick={() => setTarget(target + step)}
                />
              </Stepper>
            ) : null}
          </>
        ) : null
      }
    />
  );
}

function ClimateDetailBody({
  climate,
  mode,
  modes,
  target,
  current,
  step,
  min,
  max,
  unit,
  onMode,
  onTarget,
  onPreset,
}: {
  climate: ClimateEntity | undefined;
  mode: ClimateMode;
  modes: ClimateMode[];
  target: number | undefined;
  current: number | undefined;
  step: number;
  min: number;
  max: number;
  unit: string;
  onMode: (mode: ClimateMode) => void;
  onTarget: (value: number) => void;
  onPreset: (preset: string) => void;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  if (drag !== null && drag === target) {
    setDrag(null);
  }

  const shown = drag ?? target ?? min;
  const latest = useRef(shown);
  useEffect(() => {
    latest.current = shown;
  }, [shown]);

  const commit = (next: number) => {
    const clamped = Math.min(max, Math.max(min, Math.round(next * 100) / 100));
    latest.current = clamped;
    setDrag(clamped);
    onTarget(clamped);
  };

  const nudge = (delta: number) => commit(latest.current + delta);

  const action = climate?.action;
  const humidity = climate?.humidity;
  const presets = climate?.capabilities.presets ?? [];
  const preset = climate?.preset;
  const summary = [
    action ? title(action) : mode === 'off' ? 'Off' : title(mode),
    current !== undefined ? `${current} ${unit} now` : undefined,
    humidity !== undefined ? `${humidity}% humidity` : undefined,
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <div className={rows.stack}>
      <div className={`${rows.row} ${rows.stack}`}>
        <div className={rows.column}>
          <span className={rows.name}>Mode</span>
          <span className={rows.hint}>{summary}</span>
        </div>
        <ChipRow
          options={modes.map((m) => ({
            value: m,
            label: modeMeta(m).label,
            icon: modeMeta(m).icon,
          }))}
          value={mode}
          onChange={(value) => onMode(value as ClimateMode)}
        />
      </div>
      {target !== undefined ? (
        <div className={rows.spread}>
          <div className={rows.column}>
            <span className={rows.name}>Target</span>
            <span className={rows.hint}>Hold a button to change faster</span>
          </div>
          <Stepper>
            <StepButton
              icon={<Minus />}
              label="Decrease target"
              delta={-step}
              holdDelta={-step * 4}
              onStep={nudge}
              disabled={shown <= min}
              size={28}
            />
            <StepperValue>
              {shown} {unit}
            </StepperValue>
            <StepButton
              icon={<Plus />}
              label="Increase target"
              delta={step}
              holdDelta={step * 4}
              onStep={nudge}
              disabled={shown >= max}
              size={28}
            />
          </Stepper>
        </div>
      ) : null}
      {presets.length > 0 ? (
        <div className={`${rows.row} ${rows.stack}`}>
          <span className={rows.name}>Preset</span>
          <ChipRow
            options={presets.map((p) => ({ value: p, label: title(p) }))}
            value={preset}
            onChange={onPreset}
          />
        </div>
      ) : null}
    </div>
  );
}

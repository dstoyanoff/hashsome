import type { ClimateEntity, ClimateMode, EntityRef } from '@hashsome/core';
import { Flex, Typography } from 'e-prim';
import { useEffect, useRef, useState } from 'react';
import type { IconName } from '../icon-data.ts';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { AnimatedNumber, decimalsOf } from '../layout/animated-number.tsx';
import { ChipRow, StepButton } from '../layout/drawer-controls.tsx';
import type { EnergyChartProps } from '../layout/energy-chart.tsx';
import type { LogbookEntry } from '../layout/history-section.tsx';
import { Stepper, StepperValue } from '../layout/stepper.tsx';
import { SwatchRow } from '../layout/swatch-row.tsx';
import { IconButton, Tile } from '../layout/tile.tsx';

export interface ClimateTileProps {
  /** A thermostat or heater, as a ref like `ha:climate.living_room` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'climate'>;

  /** Defaults to the device's name. */
  name?: string;

  /** Icon id. Defaults to a thermometer. */
  icon?: IconName;

  /** Power-draw chart for the drawer, above `history`. No default source yet — pass explicitly. */
  energy?: EnergyChartProps;

  /** Recent activity, shown in the drawer: who or what changed it, and when. Left out, it comes from the backend's own record when it keeps one (Home Assistant's logbook), fetched when the person opens its History. */
  history?: LogbookEntry[];
}

interface ModeMeta {
  icon: IconName;
  color: string;
  label: string;
}

const MODE_META: Record<ClimateMode, ModeMeta> = {
  off: { icon: 'lu:power', color: '#6b6b73', label: 'Off' },
  heat: { icon: 'lu:flame', color: '#ff7a45', label: 'Heat' },
  cool: { icon: 'lu:snowflake', color: '#4dc9ff', label: 'Cool' },
  auto: { icon: 'lu:refresh-cw', color: '#5fd17a', label: 'Auto' },
  heatCool: { icon: 'lu:thermometer-sun', color: '#b36bff', label: 'Heat/Cool' },
  dry: { icon: 'lu:droplets', color: '#fbbf24', label: 'Dry' },
  fanOnly: { icon: 'lu:wind', color: '#2dd4bf', label: 'Fan' },
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
      icon={icon ?? 'lu:thermometer'}
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
              icon={picking ? 'lu:x' : modeMeta(mode).icon}
              label={picking ? 'Close modes' : 'Mode'}
              active={!picking && mode !== 'off'}
              onClick={() => setPicking((open) => !open)}
              size={16}
            />
            {target !== undefined ? (
              <Stepper>
                <IconButton
                  icon="lu:minus"
                  label="Decrease"
                  disabled={target <= min}
                  onClick={() => setTarget(target - step)}
                />
                <StepperValue>
                  {target} {unit}
                </StepperValue>
                <IconButton
                  icon="lu:plus"
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
    <Flex direction="column" gap={2}>
      <Flex direction="column" gap={2} background="surfaceRaised" radius="row" py={3.5} px={4}>
        <Flex direction="column">
          <Typography as="span" variant="bodyStrong">
            Mode
          </Typography>
          <Typography as="span" variant="secondary" color="textMuted">
            {summary}
          </Typography>
        </Flex>
        <ChipRow
          options={modes.map((m) => ({
            value: m,
            label: modeMeta(m).label,
            icon: modeMeta(m).icon,
          }))}
          value={mode}
          onChange={(value) => onMode(value as ClimateMode)}
        />
      </Flex>
      {target !== undefined ? (
        <Flex
          justify="space-between"
          align="center"
          background="surfaceRaised"
          radius="row"
          py={3.5}
          px={4}
        >
          <Flex direction="column">
            <Typography as="span" variant="bodyStrong">
              Target
            </Typography>
            <Typography as="span" variant="secondary" color="textMuted">
              Hold a button to change faster
            </Typography>
          </Flex>
          <Stepper>
            <StepButton
              icon="lu:minus"
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
              icon="lu:plus"
              label="Increase target"
              delta={step}
              holdDelta={step * 4}
              onStep={nudge}
              disabled={shown >= max}
              size={28}
            />
          </Stepper>
        </Flex>
      ) : null}
      {presets.length > 0 ? (
        <Flex direction="column" gap={2} background="surfaceRaised" radius="row" py={3.5} px={4}>
          <Typography as="span" variant="bodyStrong">
            Preset
          </Typography>
          <ChipRow
            options={presets.map((p) => ({ value: p, label: title(p) }))}
            value={preset}
            onChange={onPreset}
          />
        </Flex>
      ) : null}
    </Flex>
  );
}

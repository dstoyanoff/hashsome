/** @jsxImportSource @emotion/react */
import type { EntityRef, LightEntity } from '@hashsome/core';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { fallbackName, type EntityHandle } from '../entity-handle.ts';
import { useEntityHandle } from '../hooks.ts';
import { Box, Flex, Typography, useColorByKey } from 'e-prim';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { PowerButton, StepButton, ValueBar } from '../layout/drawer-controls.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { SwatchRow } from '../layout/swatch-row.tsx';
import { IconButton, Tile } from '../layout/tile.tsx';
import type { EnergyChartProps } from '../layout/energy-chart.tsx';
import type { LogbookEntry } from '../layout/history-section.tsx';
import {
  DEFAULT_COLOR_PRESETS,
  applyPreset,
  currentHs,
  ensureContrast,
  hexToRgb,
  isPresetActive,
  kelvinToRgb,
  presetCss,
  resolveLightColor,
  rgbCss,
  rgbToHex,
  rgbToHs,
  type LightColorPreset,
} from './light-color.ts';

export interface LightTileProps {
  /** A light, as a ref like `ha:light.kitchen_ceiling` or a handle (a custom source). */
  entity: EntityRef | EntityHandle<'light'>;

  /** Defaults to the light's name. */
  name?: string;

  /** Icon id. Defaults to a bulb. */
  icon?: IconName;

  /** Power-draw chart (e.g. a Shelly's energy monitoring), shown in the drawer above `history`.
   * No default source yet — pass explicitly. */
  energy?: EnergyChartProps;

  /** Recent activity, shown in the drawer: who or what changed it, and when. Left out, it comes from the backend's own record when it keeps one (Home Assistant's logbook), fetched when the person opens its History. */
  history?: LogbookEntry[];

  /** Swatches shown when the palette button is opened. Default: four white temperatures. Each takes `kelvin` (color-temp lights) and/or `hs` (color lights) and is skipped if the light can't apply it. */
  colors?: LightColorPreset[];

  /** Holding the tile opens a drawer with the full controls, the color bars, the energy chart and the recent activity. `false` leaves the tile as it is without it: a toggle that dims by dragging, with the color swatches if the light has color, and no drawer built, which is lighter on a small display. Default `true`. */
  drawer?: boolean;
}

const HUE_GRADIENT =
  'linear-gradient(to right, hsl(0 100% 50%), hsl(60 100% 50%), hsl(120 100% 50%), hsl(180 100% 50%), hsl(240 100% 50%), hsl(300 100% 50%), hsl(360 100% 50%))';

const kelvinGradient = (min: number, max: number) =>
  `linear-gradient(to right, ${[0, 0.25, 0.5, 0.75, 1]
    .map((f) => rgbCss(kelvinToRgb(min + f * (max - min))))
    .join(', ')})`;

/** Toggle, drag-to-dim (if dimmable) and a color overlay + drawer picker (if color capable). */
export function LightTile({
  entity,
  name,
  icon,
  energy,
  history,
  colors = DEFAULT_COLOR_PRESETS,
  drawer = true,
}: LightTileProps) {
  const handle = useEntityHandle('light', entity);
  const [picking, setPicking] = useState(false);
  const [colorOpen, setColorOpen] = useState(false);

  const { status } = handle;
  const light = handle.entity;
  const on = light?.on === true;
  const supportsTemp = light?.capabilities.colorTemperature === true;
  const supportsHs = light?.capabilities.color === true;
  const dimmable = light?.capabilities.brightness === true;
  const color = supportsTemp || supportsHs;
  const brightness = light?.brightness;
  const percent = brightness === undefined ? undefined : Math.round(brightness * 100);
  const rgb = resolveLightColor(light);
  const chipBg = hexToRgb(useColorByKey('surfaceRaised') ?? '');
  const paletteColor = rgb ? rgbCss(chipBg ? ensureContrast(rgb, chipBg) : rgb) : undefined;
  const caps = { colorTemp: supportsTemp, hs: supportsHs };
  const swatches = colors.flatMap((preset) => {
    const data = applyPreset(preset, caps);
    return data ? [{ preset, data }] : [];
  });

  const showPicker = color && status === 'ready';

  return (
    <Tile
      label={name ?? light?.name ?? fallbackName(entity)}
      icon={icon ?? (on ? 'lu:lightbulb' : 'lu:lightbulb-off')}
      kind="Light"
      status={status}
      active={on}
      secondary={on ? (dimmable && percent !== undefined ? `${percent}%` : undefined) : 'Off'}
      onPress={() => void handle.command('toggle')}
      {...(drawer
        ? {
            detail: (
              <LightDetailBody
                on={on}
                percent={dimmable ? percent : undefined}
                onToggle={() => void handle.command('toggle')}
                {...(dimmable
                  ? {
                      onBrightness: (next: number) =>
                        void handle.command('setBrightness', { brightness: next / 100 }),
                    }
                  : {})}
                colorSection={
                  showPicker ? (
                    <LightColorSection
                      light={light}
                      open={colorOpen}
                      onToggle={() => setColorOpen((open) => !open)}
                      supportsTemp={supportsTemp}
                      supportsHs={supportsHs}
                      onKelvin={(kelvin) => void handle.command('setColorTemperature', { kelvin })}
                      onHue={(hue) =>
                        void handle.command('setColor', {
                          hue,
                          saturation: currentHs(light)[1] || 100,
                        })
                      }
                      onPicked={(hex) => {
                        const picked = hexToRgb(hex);
                        if (picked) {
                          const [hue, saturation] = rgbToHs(picked);
                          void handle.command('setColor', { hue, saturation });
                        }
                      }}
                    />
                  ) : null
                }
              />
            ),
          }
        : {})}
      {...(drawer && energy ? { energy } : {})}
      {...(drawer && history ? { history } : {})}
      {...(drawer && typeof entity === 'string' ? { logbook: entity } : {})}
      {...(dimmable
        ? {
            fill: on ? (brightness ?? 1) : 0,
            onFillChange: (fill: number) =>
              void handle.command('setBrightness', { brightness: fill }),
          }
        : {})}
      {...(showPicker && picking
        ? {
            // A render prop `Tile` calls, not a component of its own.
            // oxlint-disable-next-line react/no-unstable-nested-components
            overlay: ({ openDetail }: { openDetail: () => void }) => (
              <SwatchRow
                items={swatches.map(({ preset, data }) => ({
                  key: preset.label,
                  label: preset.label,
                  selected: isPresetActive(data, light),
                  color: presetCss(preset),
                  onPick: () => {
                    void (data.command === 'setColorTemperature'
                      ? handle.command('setColorTemperature', data.args)
                      : handle.command('setColor', data.args));

                    setPicking(false);
                  },
                }))}
                // The custom color is a card in the drawer: without one there is no such button.
                {...(drawer
                  ? {
                      customLabel: 'Custom color',
                      onCustom: () => {
                        setPicking(false);
                        setColorOpen(true);
                        openDetail();
                      },
                    }
                  : {})}
              />
            ),
          }
        : {})}
      trailing={
        showPicker ? (
          <IconButton
            icon={picking ? 'lu:x' : 'lu:palette'}
            label={picking ? 'Close colors' : 'Color'}
            active={false}
            onClick={() => setPicking((open) => !open)}
            size={16}
            {...(on && paletteColor && !picking ? { color: paletteColor } : {})}
          />
        ) : null
      }
    />
  );
}

/** The drawer's collapsible "Color" card: temperature and hue bars. `custom` from the card's
 * swatch overlay opens the drawer with this already expanded. */
function LightColorSection({
  light,
  open,
  onToggle,
  supportsTemp,
  supportsHs,
  onKelvin,
  onHue,
  onPicked,
}: {
  light: LightEntity | undefined;
  open: boolean;
  onToggle: () => void;
  supportsTemp: boolean;
  supportsHs: boolean;
  onKelvin: (kelvin: number) => void;
  onHue: (hue: number) => void;
  onPicked: (hex: string) => void;
}) {
  const minK = light?.capabilities.kelvinRange?.min ?? 2000;
  const maxK = light?.capabilities.kelvinRange?.max ?? 6500;
  const rgb = resolveLightColor(light);
  const kelvin = Math.min(
    maxK,
    Math.max(minK, light?.color?.mode === 'temperature' ? light.color.kelvin : 2700),
  );

  const hue = rgb ? rgbToHs(rgb)[0] : 0;
  const [pickedHex, setPickedHex] = useState<string | null>(null);
  const pickTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(pickTimer.current), []);
  const hex = pickedHex ?? (rgb ? rgbToHex(rgb) : '#ffffff');
  const [dragK, setDragK] = useState<number | null>(null);
  const [dragH, setDragH] = useState<number | null>(null);
  if (dragK !== null && dragK === kelvin) {
    setDragK(null);
  }

  if (dragH !== null && dragH === hue) {
    setDragH(null);
  }

  return (
    <Flex direction="column" gap={2} py={3.5}>
      <PlainButton
        aria-expanded={open}
        onClick={onToggle}
        justify="space-between"
        align="center"
        color="text"
      >
        <Typography as="span" variant="bodyStrong">
          Color
        </Typography>
        <Flex align="center" gap={2}>
          {rgb ? (
            <Box
              as="span"
              width={16}
              height={16}
              radius="full"
              aria-hidden="true"
              css={{ background: rgbCss(rgb) }}
            />
          ) : null}
          <Icon name={open ? 'lu:chevron-up' : 'lu:chevron-down'} size={16} />
        </Flex>
      </PlainButton>
      {open ? (
        <Flex direction="column" gap={3} pt={1}>
          {supportsTemp ? (
            <Flex direction="column" gap={2}>
              <Flex justify="space-between" align="center">
                <Typography as="span" variant="secondary" color="textMuted">
                  Temperature
                </Typography>
                <Typography as="span" variant="secondary" color="textMuted">
                  {dragK ?? kelvin} K
                </Typography>
              </Flex>
              <ValueBar
                label="Color temperature"
                value={dragK ?? kelvin}
                min={minK}
                max={maxK}
                keyStep={100}
                round={50}
                gradient={kelvinGradient(minK, maxK)}
                onDrag={setDragK}
                onCommit={(next) => {
                  setDragK(next);
                  onKelvin(next);
                }}
              />
            </Flex>
          ) : null}
          {supportsHs ? (
            <Flex direction="column" gap={2}>
              <Typography as="span" variant="secondary" color="textMuted">
                Hue
              </Typography>
              <ValueBar
                label="Hue"
                value={dragH ?? hue}
                min={0}
                max={360}
                keyStep={10}
                gradient={HUE_GRADIENT}
                onDrag={setDragH}
                onCommit={(next) => {
                  setDragH(next);
                  onHue(next);
                }}
              />
              <Flex justify="space-between" align="center">
                <Typography as="span" variant="secondary" color="textMuted">
                  Any color
                </Typography>
                <Flex align="center" gap={2}>
                  <Box
                    as="input"
                    width={16}
                    height={16}
                    p={0}
                    radius="full"
                    cursor="pointer"
                    css={{
                      background: 'none',
                      '&::-webkit-color-swatch-wrapper': { padding: 0 },
                      '&::-webkit-color-swatch': { border: 0, borderRadius: '50%' },
                      '&::-moz-color-swatch': { border: 0, borderRadius: '50%' },
                    }}
                    type="color"
                    aria-label="Pick any color"
                    title="Open the color picker"
                    value={hex}
                    onChange={(event) => {
                      const next = event.target.value;
                      setPickedHex(next);
                      clearTimeout(pickTimer.current);
                      pickTimer.current = setTimeout(() => onPicked(next), 200);
                    }}
                  />
                </Flex>
              </Flex>
            </Flex>
          ) : null}
        </Flex>
      ) : null}
    </Flex>
  );
}

function LightDetailBody({
  on,
  percent,
  onToggle,
  onBrightness,
  colorSection,
}: {
  on: boolean;
  percent?: number | undefined;
  onToggle: () => void;
  onBrightness?: (percent: number) => void;
  colorSection?: ReactNode;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const shown = on ? (percent ?? 100) : 0;
  if (drag !== null && drag === shown) {
    setDrag(null);
  }

  const value = drag ?? shown;
  const commit = (next: number) => {
    setDrag(next);
    onBrightness?.(next);
  };

  const latest = useRef(value);
  useEffect(() => {
    latest.current = value;
  }, [value]);

  const step = (delta: number) => {
    const next = Math.min(100, Math.max(0, latest.current + delta));
    latest.current = next;
    commit(next);
  };

  return (
    <Flex direction="column" gap={2}>
      <Flex justify="space-between" align="center" py={3.5}>
        <Flex direction="column">
          <Typography as="span" variant="bodyStrong">
            Power
          </Typography>
          <Typography as="span" variant="secondary" color="textMuted">
            {on ? (percent !== undefined ? `On · ${percent}%` : 'On') : 'Off'}
          </Typography>
        </Flex>
        <PowerButton on={on} onToggle={onToggle} />
      </Flex>
      {onBrightness ? (
        <Flex direction="column" gap={2} py={3.5}>
          <Flex justify="space-between" align="center">
            <Typography as="span" variant="bodyStrong">
              Brightness
            </Typography>
            <Typography as="span" variant="secondary" color="textMuted">
              {value}%
            </Typography>
          </Flex>
          <Flex align="center" gap={2}>
            <StepButton
              plain
              icon="lu:minus"
              label="Decrease brightness"
              delta={-1}
              onStep={step}
            />
            <Box grow={1}>
              <ValueBar
                label="Brightness"
                track="surfaceRaised"
                value={value}
                min={0}
                max={100}
                keyStep={5}
                onDrag={setDrag}
                onCommit={commit}
              />
            </Box>
            <StepButton plain icon="lu:plus" label="Increase brightness" delta={1} onStep={step} />
          </Flex>
        </Flex>
      ) : null}
      {colorSection}
    </Flex>
  );
}

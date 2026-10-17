/** @jsxImportSource @emotion/react */
import type { EntityRef, ForecastPoint, ForecastResult, WeatherEntity } from '@hashsome/core';
import { Box, Flex, Typography } from '../style/prim.tsx';
import type { ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
import { useEntityHandle } from '../hooks.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from '../layout/detail-provider.tsx';
import { SeriesChart } from '../layout/series-chart.tsx';
import { useWeatherForecast } from '../use-weather-forecast.ts';
import { WeatherIcon } from './weather-icon.tsx';
import { compass, CONDITION_LABEL, uvLevel } from './weather-look.ts';

/** How many hours the chart and the hourly cards cover. */
const HOURS = 24;

/** Hours between the labelled points under the chart in the narrow drawer. */
const EVERY = 4;

const degrees = (value: number | undefined) =>
  value === undefined ? '–' : `${Math.round(value)}°`;

const tenths = (value: number) => Math.round(value * 10) / 10;

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString();

function dayName(iso: string, now: Date): string {
  const date = new Date(iso);
  if (sameDay(date, now)) {
    return 'Today';
  }

  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  return sameDay(date, tomorrow) ? 'Tomorrow' : date.toLocaleDateString([], { weekday: 'long' });
}

const hourName = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: 'numeric' });

/** `15 km/h from WNW`, with what there is of it. */
function windText(speed: number, bearing: number | undefined, unit: string | undefined): string {
  return `${Math.round(speed)}${unit ? ` ${unit}` : ''}${bearing !== undefined ? ` from ${compass(bearing)}` : ''}`;
}

/** `60% · 0.4 mm`, for the rain a step has: a chance, an amount, or both. Nothing for a dry step. */
function rainText(point: ForecastPoint, unit: string | undefined): string | undefined {
  const parts = [
    ...(point.precipitationProbability ? [`${Math.round(point.precipitationProbability)}%`] : []),
    ...(point.precipitation ? [`${tenths(point.precipitation)} ${unit ?? 'mm'}`] : []),
  ];

  return parts.length > 0 ? parts.join(' · ') : undefined;
}

/** Everything a step knows, a line each, for the chart's hover: the sky, the wind, the rain, the
 * air, the sun and the pressure. What the source did not give is left out. */
function describeStep(point: ForecastPoint, result: ForecastResult | undefined): string[] {
  const rain = rainText(point, result?.precipitationUnit);
  const air = [
    ...(point.humidity !== undefined ? [`Humidity ${Math.round(point.humidity)}%`] : []),
    ...(point.cloudCoverage !== undefined
      ? [`Cloud cover ${Math.round(point.cloudCoverage)}%`]
      : []),
  ];

  const sun = [
    ...(point.uvIndex !== undefined
      ? [`UV ${tenths(point.uvIndex)} (${uvLevel(point.uvIndex)})`]
      : []),
    ...(point.apparentTemperature !== undefined
      ? [`Feels like ${degrees(point.apparentTemperature)}`]
      : []),
  ];

  return [
    CONDITION_LABEL[point.condition],
    ...(point.windSpeed !== undefined
      ? [
          `Wind ${windText(point.windSpeed, point.windBearing, result?.windUnit)}${point.windGustSpeed !== undefined ? `, gusts ${Math.round(point.windGustSpeed)}` : ''}`,
        ]
      : []),
    ...(rain ? [`Rain ${rain}`] : []),
    ...(air.length > 0 ? [air.join(' · ')] : []),
    ...(sun.length > 0 ? [sun.join(' · ')] : []),
    ...(point.pressure !== undefined
      ? [
          `Pressure ${Math.round(point.pressure)}${result?.pressureUnit ? ` ${result.pressureUnit}` : ''}`,
        ]
      : []),
  ];
}

function ConditionIcon({
  condition,
  size,
}: {
  condition: ForecastPoint['condition'];
  size: number;
}) {
  return (
    <Flex as="span">
      <WeatherIcon condition={condition} size={size} />
    </Flex>
  );
}

/** An arrow that points the way the wind blows (it comes from `bearing`, so it points the other way). */
function WindArrow({ bearing, size }: { bearing: number; size: number }) {
  return (
    <Flex as="span" css={{ transform: `rotate(${bearing + 180}deg)` }}>
      <Icon name="lu:arrow-up" size={size} />
    </Flex>
  );
}

/** One small reading with an icon in front of it. */
function Metric({ icon, children }: { icon: ReactNode | IconName; children: ReactNode }) {
  return (
    <Flex align="center" gap={1} color="textMuted">
      {typeof icon === 'string' ? <Icon name={icon as IconName} size={12} /> : icon}
      <Typography as="span" variant="secondary" color="textMuted" noWrap>
        {children}
      </Typography>
    </Flex>
  );
}

/** The wind as a metric: an arrow the way it blows, and its speed. */
function WindMetric({ point, unit }: { point: ForecastPoint; unit?: string | undefined }) {
  return point.windSpeed === undefined ? null : (
    <Metric
      icon={
        point.windBearing !== undefined ? (
          <WindArrow bearing={point.windBearing} size={12} />
        ) : (
          'lu:wind'
        )
      }
    >
      {Math.round(point.windSpeed)}
      {unit ? ` ${unit}` : ''}
    </Metric>
  );
}

/** A tile with a label and a value, for the current readings of the expanded drawer. */
function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Flex direction="column" gap={0.5} border radius="row" px={3} py={2.5}>
      <Typography as="span" variant="secondary" color="textMuted">
        {label}
      </Typography>
      <Typography as="span" variant="bodyStrong">
        {children}
      </Typography>
    </Flex>
  );
}

/** The readings the weather has now, from the entity and, for what it lacks, the hour that is
 * under way (a source may give UV or cloud cover only in its forecast). */
function currentReadings(weather: WeatherEntity | undefined, soon: ForecastPoint | undefined) {
  return {
    uv: weather?.uvIndex ?? soon?.uvIndex,
    cloud: weather?.cloudCoverage ?? soon?.cloudCoverage,
    feels: weather?.apparentTemperature ?? soon?.apparentTemperature,
    pressure: weather?.pressure ?? soon?.pressure,
    humidity: weather?.humidity ?? soon?.humidity,
  };
}

export interface WeatherForecastProps {
  /** A weather entity, as a ref like `ha:weather.forecast_home`. The forecasts it offers (hourly, daily) are read through the runtime. */
  entity: EntityRef;

  /** Lay it out for a wide space (readings tiles, a card for each hour, more columns for the days) or for a narrow one. Left out, it follows the drawer it is in. */
  expanded?: boolean;
}

/** A weather entity's forecast: the weather now, the next 24 hours as a chart (hover it for everything
 * about an hour) and the days ahead. The top bar's weather chip shows it in a drawer; a dashboard can
 * put it in a page of its own, with `expanded` for the full-size layout. Wide, it adds the current
 * readings and a card for each hour. A source with no forecast shows only the weather now. */
export function WeatherForecast({ entity, expanded: layout }: WeatherForecastProps) {
  const handle = useEntityHandle('weather', entity);
  const weather = handle.entity;
  const hourly = useWeatherForecast(
    weather?.forecasts?.includes('hourly') ? entity : undefined,
    'hourly',
  );

  const daily = useWeatherForecast(
    weather?.forecasts?.includes('daily') ? entity : undefined,
    'daily',
  );

  const { detail } = useDetail();
  const expanded = layout ?? detail?.expanded ?? false;
  const now = new Date();
  const hours = (hourly.result?.points ?? []).slice(0, HOURS);
  const along = hours.filter((_, index) => index % EVERY === 0);
  const reading = currentReadings(weather, hours[0]);
  const windUnit = hourly.result?.windUnit ?? weather?.windUnit;

  const days = daily.result?.points ?? [];
  const lows = days.flatMap((day) => (day.low !== undefined ? [day.low] : []));
  const highs = days.flatMap((day) => (day.temperature !== undefined ? [day.temperature] : []));
  const coldest = Math.min(...lows);
  const warmest = Math.max(...highs);
  const span = warmest - coldest;

  return (
    <Flex direction="column" gap={5}>
      <Flex align="center" gap={3}>
        <ConditionIcon condition={weather?.condition ?? 'unknown'} size={expanded ? 56 : 40} />
        <Flex direction="column">
          <Typography as="span" variant="heading">
            {degrees(weather?.temperature)}
          </Typography>
          <Typography as="span" variant="secondary" color="textMuted">
            {CONDITION_LABEL[weather?.condition ?? 'unknown']}
            {reading.humidity !== undefined ? ` · ${Math.round(reading.humidity)}% humidity` : ''}
            {reading.uv !== undefined ? ` · UV ${tenths(reading.uv)} (${uvLevel(reading.uv)})` : ''}
          </Typography>
          {weather?.windSpeed !== undefined ? (
            <Flex align="center" gap={1} color="textMuted">
              {weather.windBearing !== undefined ? (
                <WindArrow bearing={weather.windBearing} size={12} />
              ) : null}
              <Typography as="span" variant="secondary" color="textMuted">
                Wind {windText(weather.windSpeed, weather.windBearing, weather.windUnit)}
              </Typography>
            </Flex>
          ) : null}
        </Flex>
      </Flex>

      {expanded ? (
        <Box
          css={{
            display: 'grid',
            gap: 12,
            gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
          }}
        >
          {reading.feels !== undefined ? (
            <Stat label="Feels like">{degrees(reading.feels)}</Stat>
          ) : null}
          {reading.humidity !== undefined ? (
            <Stat label="Humidity">{Math.round(reading.humidity)}%</Stat>
          ) : null}
          {weather?.windSpeed !== undefined ? (
            <Stat label="Wind">
              {windText(weather.windSpeed, weather.windBearing, weather.windUnit)}
            </Stat>
          ) : null}
          {reading.uv !== undefined ? (
            <Stat label="UV index">
              {tenths(reading.uv)} · {uvLevel(reading.uv)}
            </Stat>
          ) : null}
          {reading.cloud !== undefined ? (
            <Stat label="Cloud cover">{Math.round(reading.cloud)}%</Stat>
          ) : null}
          {reading.pressure !== undefined ? (
            <Stat label="Pressure">
              {Math.round(reading.pressure)}
              {weather?.pressureUnit ? ` ${weather.pressureUnit}` : ''}
            </Stat>
          ) : null}
        </Box>
      ) : null}

      {hours.length > 0 ? (
        <Flex direction="column" gap={1}>
          <Typography as="h3" variant="label" color="textMuted" m={0}>
            Next 24 hours
          </Typography>
          {/* The temperature as a curve; hover it for everything about that hour. */}
          <SeriesChart
            samples={hours.flatMap((hour) =>
              hour.temperature !== undefined
                ? [
                    {
                      timestamp: hour.timestamp,
                      value: hour.temperature,
                      notes: describeStep(hour, hourly.result),
                    },
                  ]
                : [],
            )}
            range="1d"
            expanded={expanded}
            unit={hourly.result?.unit ?? '°'}
            fitDomain
            height={expanded ? 180 : 88}
          />
          {expanded ? (
            // The 24 hours in one row, each a column with no box of its own: as many as fit show, and
            // the rest are a swipe or a scroll away.
            <Box mt={2}>
              <Flex css={{ overflowX: 'auto', paddingBottom: 4 }}>
                {hours.map((hour) => {
                  const rain = rainText(hour, hourly.result?.precipitationUnit);
                  return (
                    <Flex
                      key={hour.timestamp}
                      direction="column"
                      align="center"
                      gap={1.5}
                      px={2}
                      py={1}
                      css={{ flex: '1 0 84px' }}
                    >
                      <Typography as="span" variant="secondary" color="textMuted">
                        {hourName(hour.timestamp)}
                      </Typography>
                      <ConditionIcon condition={hour.condition} size={26} />
                      <Typography as="span" variant="bodyStrong">
                        {degrees(hour.temperature)}
                      </Typography>
                      <Flex direction="column" align="center" gap={0.5}>
                        {rain ? <Metric icon="lu:umbrella">{rain}</Metric> : null}
                        <WindMetric point={hour} unit={windUnit} />
                        {hour.humidity !== undefined ? (
                          <Metric icon="lu:droplets">{Math.round(hour.humidity)}%</Metric>
                        ) : null}
                        {hour.uvIndex !== undefined && hour.uvIndex > 0 ? (
                          <Metric icon="lu:sun">UV {tenths(hour.uvIndex)}</Metric>
                        ) : null}
                      </Flex>
                    </Flex>
                  );
                })}
              </Flex>
            </Box>
          ) : (
            // A few hours along the curve, with their sky and temperature.
            <Flex justify="space-between" mt={1}>
              {along.map((hour) => (
                <Flex
                  key={hour.timestamp}
                  direction="column"
                  align="center"
                  gap={1}
                  grow={1}
                  css={{ flexBasis: 0 }}
                >
                  <Typography as="span" variant="secondary" color="textMuted">
                    {hourName(hour.timestamp)}
                  </Typography>
                  <ConditionIcon condition={hour.condition} size={18} />
                  <Typography as="span" variant="label">
                    {degrees(hour.temperature)}
                  </Typography>
                </Flex>
              ))}
            </Flex>
          )}
        </Flex>
      ) : null}

      {days.length > 0 ? (
        <Flex direction="column" gap={2}>
          <Typography as="h3" variant="label" color="textMuted" m={0}>
            {days.length} days
          </Typography>
          <Flex as="ul" direction="column" gap={1} m={0} p={0} css={{ listStyle: 'none' }}>
            {days.map((day) => {
              const rain = rainText(day, daily.result?.precipitationUnit);
              return (
                <Flex as="li" key={day.timestamp} align="center" gap={3} py={1.5}>
                  <Typography as="span" variant="body" width={104} noWrap>
                    {dayName(day.timestamp, now)}
                  </Typography>
                  <ConditionIcon condition={day.condition} size={20} />
                  <Typography
                    as="span"
                    variant="secondary"
                    color="textMuted"
                    width={expanded ? 90 : 40}
                    noWrap
                  >
                    {rain ?? ''}
                  </Typography>
                  <Typography
                    as="span"
                    variant="label"
                    color="textMuted"
                    width={36}
                    css={{ textAlign: 'right' }}
                  >
                    {degrees(day.low)}
                  </Typography>
                  <Box
                    grow={1}
                    height={6}
                    radius="full"
                    background="surfaceRaised"
                    position="relative"
                  >
                    {span > 0 && day.low !== undefined && day.temperature !== undefined ? (
                      <Box
                        position="absolute"
                        height={6}
                        radius="full"
                        background="accent"
                        css={{
                          left: `${((day.low - coldest) / span) * 100}%`,
                          width: `${((day.temperature - day.low) / span) * 100}%`,
                        }}
                      />
                    ) : null}
                  </Box>
                  <Typography as="span" variant="label" width={36}>
                    {degrees(day.temperature)}
                  </Typography>
                  {expanded ? (
                    <Flex gap={4} css={{ flex: 'none' }}>
                      <Box width={84}>
                        <WindMetric point={day} unit={daily.result?.windUnit} />
                      </Box>
                      <Box width={64}>
                        {day.humidity !== undefined ? (
                          <Metric icon="lu:droplets">{Math.round(day.humidity)}%</Metric>
                        ) : null}
                      </Box>
                      <Box width={72}>
                        {day.uvIndex !== undefined ? (
                          <Metric icon="lu:sun">UV {tenths(day.uvIndex)}</Metric>
                        ) : null}
                      </Box>
                    </Flex>
                  ) : null}
                </Flex>
              );
            })}
          </Flex>
        </Flex>
      ) : null}
    </Flex>
  );
}

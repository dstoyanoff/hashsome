import type { EntityRef, ForecastPoint, ForecastResult, WeatherEntity } from '@hashsome/core';
import type { CSSProperties, ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
import { useEntityHandle } from '../hooks.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from '../layout/detail-provider.tsx';
import { SeriesChart } from '../layout/series-chart.tsx';
import { useWeatherForecast } from '../use-weather-forecast.ts';
import styles from './weather-forecast.module.css';
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
    <span className={styles.glyph}>
      <WeatherIcon condition={condition} size={size} />
    </span>
  );
}

/** An arrow that points the way the wind blows (it comes from `bearing`, so it points the other way). */
function WindArrow({ bearing, size }: { bearing: number; size: number }) {
  return (
    <span className={styles.arrow} style={{ '--turn': `${bearing + 180}deg` } as CSSProperties}>
      <Icon name="lu:arrow-up" size={size} />
    </span>
  );
}

/** One small reading with an icon in front of it. */
function Metric({ icon, children }: { icon: ReactNode | IconName; children: ReactNode }) {
  return (
    <span className={styles.metric}>
      {typeof icon === 'string' ? <Icon name={icon as IconName} size={12} /> : icon}
      <span className={styles.metricText}>{children}</span>
    </span>
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
    <div className={styles.stat}>
      <span className={styles.statLabel}>{label}</span>
      <span className={styles.statValue}>{children}</span>
    </div>
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
    <div className={styles.forecast}>
      <div className={styles.now}>
        <ConditionIcon condition={weather?.condition ?? 'unknown'} size={expanded ? 56 : 40} />
        <div className={styles.nowText}>
          <span className={styles.temp}>{degrees(weather?.temperature)}</span>
          <span className={styles.sub}>
            {CONDITION_LABEL[weather?.condition ?? 'unknown']}
            {reading.humidity !== undefined ? ` · ${Math.round(reading.humidity)}% humidity` : ''}
            {reading.uv !== undefined ? ` · UV ${tenths(reading.uv)} (${uvLevel(reading.uv)})` : ''}
          </span>
          {weather?.windSpeed !== undefined ? (
            <span className={styles.windNow}>
              {weather.windBearing !== undefined ? (
                <WindArrow bearing={weather.windBearing} size={12} />
              ) : null}
              <span className={styles.sub}>
                Wind {windText(weather.windSpeed, weather.windBearing, weather.windUnit)}
              </span>
            </span>
          ) : null}
        </div>
      </div>

      {expanded ? (
        <div className={styles.stats}>
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
        </div>
      ) : null}

      {hours.length > 0 ? (
        <div className={styles.block}>
          <h3 className={styles.heading}>Next 24 hours</h3>
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
            <div className={styles.hours}>
              {hours.map((hour) => {
                const rain = rainText(hour, hourly.result?.precipitationUnit);
                return (
                  <div key={hour.timestamp} className={styles.hour}>
                    <span className={styles.hourName}>{hourName(hour.timestamp)}</span>
                    <ConditionIcon condition={hour.condition} size={26} />
                    <span className={styles.hourTemp}>{degrees(hour.temperature)}</span>
                    <div className={styles.metrics}>
                      {rain ? <Metric icon="lu:umbrella">{rain}</Metric> : null}
                      <WindMetric point={hour} unit={windUnit} />
                      {hour.humidity !== undefined ? (
                        <Metric icon="lu:droplets">{Math.round(hour.humidity)}%</Metric>
                      ) : null}
                      {hour.uvIndex !== undefined && hour.uvIndex > 0 ? (
                        <Metric icon="lu:sun">UV {tenths(hour.uvIndex)}</Metric>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className={styles.along}>
              {along.map((hour) => (
                <div key={hour.timestamp} className={styles.alongHour}>
                  <span className={styles.hourName}>{hourName(hour.timestamp)}</span>
                  <ConditionIcon condition={hour.condition} size={18} />
                  <span className={styles.alongTemp}>{degrees(hour.temperature)}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      ) : null}

      {days.length > 0 ? (
        <div className={styles.days} data-expanded={expanded}>
          <h3 className={styles.heading}>{days.length} days</h3>
          <ul className={styles.dayList}>
            {days.map((day) => {
              const rain = rainText(day, daily.result?.precipitationUnit);
              return (
                <li key={day.timestamp} className={styles.day}>
                  <span className={styles.dayName}>{dayName(day.timestamp, now)}</span>
                  <ConditionIcon condition={day.condition} size={20} />
                  <span className={styles.dayRain}>{rain ?? ''}</span>
                  <span className={styles.low}>{degrees(day.low)}</span>
                  <div className={styles.range}>
                    {span > 0 && day.low !== undefined && day.temperature !== undefined ? (
                      <div
                        className={styles.rangeBar}
                        style={
                          {
                            '--from': `${((day.low - coldest) / span) * 100}%`,
                            '--width': `${((day.temperature - day.low) / span) * 100}%`,
                          } as CSSProperties
                        }
                      />
                    ) : null}
                  </div>
                  <span className={styles.high}>{degrees(day.temperature)}</span>
                  {expanded ? (
                    <div className={styles.extras}>
                      <div className={styles.wind}>
                        <WindMetric point={day} unit={daily.result?.windUnit} />
                      </div>
                      <div className={styles.humidity}>
                        {day.humidity !== undefined ? (
                          <Metric icon="lu:droplets">{Math.round(day.humidity)}%</Metric>
                        ) : null}
                      </div>
                      <div className={styles.uv}>
                        {day.uvIndex !== undefined ? (
                          <Metric icon="lu:sun">UV {tenths(day.uvIndex)}</Metric>
                        ) : null}
                      </div>
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

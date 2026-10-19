// Writes the weather condition icons `ui` draws in several colors (the web's `CONDITION_LOOK` in
// packages/ui/src/entities/weather-look.tsx: a cloud blue, a sun yellow, the rain a deeper blue, each shape its own).
//
// A tinted vector drawable has one color, so a condition is split by what colors it:
//   ic_weather_<condition>.xml        the parts with a color of their own, baked in (absent when there are none)
//   ic_weather_<condition>_plain.xml  the parts drawn in the surrounding text color, white so a tint can recolor them (absent when there are none)
// and the Kotlin table `WeatherLook.kt` says which exist. Shapes are numbered as the web numbers them: the i-th shape of the
// lucide icon, which here is the i-th <path> of `ic_ui_<icon>.xml` (the converter keeps them one to one; this script checks).
//
// Run it after `hashsome android icons ui ...` (README "Generating the icons"):
//   node android/ui/tools/gen-weather-icons.mjs

import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const drawables = join(root, 'src/main/res/drawable');
const svgs = join(root, 'icons');
const kotlin = join(root, 'src/main/kotlin/dev/hashsome/ui/WeatherLook.kt');

const SUN = '#FBBF24';
const CLOUD = '#60A5FA';
const RAIN = '#3B82F6';
const SNOW = '#7DD3FC';

// condition (wire name) -> [kotlin name, label, lucide icon, color or null, { shape index: color }]
const LOOK = [
  ['sunny', 'Sunny', 'Sunny', 'sun', SUN, {}],
  ['clear-night', 'ClearNight', 'Clear', 'moon', '#A5B4FC', {}],
  // The cloud is the last shape of `cloud-sun`; the rest is the sun.
  ['partlycloudy', 'PartlyCloudy', 'Partly cloudy', 'cloud-sun', SUN, { 5: CLOUD }],
  ['cloudy', 'Cloudy', 'Cloudy', 'cloud', CLOUD, {}],
  ['fog', 'Fog', 'Fog', 'cloud-fog', null, { 0: CLOUD }],
  ['rainy', 'Rainy', 'Rain', 'cloud-rain', RAIN, { 0: CLOUD }],
  ['pouring', 'Pouring', 'Heavy rain', 'cloud-rain-wind', RAIN, { 0: CLOUD }],
  ['snowy', 'Snowy', 'Snow', 'cloud-snow', SNOW, { 0: CLOUD }],
  ['snowy-rainy', 'SnowyRainy', 'Sleet', 'cloud-snow', RAIN, { 0: CLOUD }],
  ['hail', 'Hail', 'Hail', 'cloud-hail', SNOW, { 0: CLOUD }],
  ['lightning', 'Lightning', 'Thunderstorm', 'cloud-lightning', SUN, { 0: CLOUD }],
  [
    'lightning-rainy',
    'LightningRainy',
    'Thunderstorm with rain',
    'cloud-lightning',
    SUN,
    { 0: CLOUD },
  ],
  ['windy', 'Windy', 'Windy', 'wind', null, {}],
  ['exceptional', 'Exceptional', 'Exceptional weather', 'triangle-alert', '#F2554A', {}],
  ['unknown', 'Unknown', 'Unknown', 'cloud', null, {}],
];

const argb = (hex) => `0xFF${hex.slice(1).toUpperCase()}`;
const snake = (wire) => wire.replace(/-/g, '_');

/** The <path .../> lines of a generated drawable. */
function pathsOf(name) {
  const xml = readFileSync(join(drawables, `ic_ui_${name.replace(/-/g, '_')}.xml`), 'utf8');
  return xml.split('\n').filter((line) => line.trim().startsWith('<path'));
}

function shapeCount(name) {
  const svg = readFileSync(join(svgs, `${name}.svg`), 'utf8');
  return (svg.match(/<(path|circle|ellipse|line|polyline|polygon|rect)\b/g) ?? []).length;
}

const vector = (paths) =>
  [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
    '    android:width="24dp" android:height="24dp"',
    '    android:viewportWidth="24" android:viewportHeight="24">',
    ...paths,
    '</vector>',
    '',
  ].join('\n');

const recolor = (line, hex) =>
  line.replace(/android:strokeColor="[^"]*"/, `android:strokeColor="${hex.replace('#', '#FF')}"`);

const rows = [];
for (const [wire, name, label, icon, color, parts] of LOOK) {
  const paths = pathsOf(icon);
  if (paths.length !== shapeCount(icon)) {
    throw new Error(
      `${icon}: ${shapeCount(icon)} shapes in the SVG but ${paths.length} paths in the drawable`,
    );
  }

  const colored = [];
  const plain = [];
  paths.forEach((line, i) => {
    const own = parts[i] ?? color;
    if (own) {
      colored.push(recolor(line, own));
    } else {
      plain.push(line);
    }
  });

  const base = `ic_weather_${snake(wire)}`;
  if (colored.length > 0) {
    writeFileSync(join(drawables, `${base}.xml`), vector(colored));
  }

  if (plain.length > 0) {
    writeFileSync(join(drawables, `${base}_plain.xml`), vector(plain));
  }

  rows.push({
    wire,
    name,
    label,
    base,
    color,
    parts,
    hasColored: colored.length > 0,
    hasPlain: plain.length > 0,
  });
}

const entry = (r) => {
  const main = r.hasColored ? `R.drawable.${r.base}` : `R.drawable.${r.base}_plain`;
  const plain = r.hasColored && r.hasPlain ? `Icon(R.drawable.${r.base}_plain)` : 'null';
  const partList = Object.entries(r.parts)
    .map(([i, c]) => `${i} to ${argb(c)}.toInt()`)
    .join(', ');

  return [
    `        WeatherCondition.${r.name} to Look(`,
    `            icon = Icon(${main}),`,
    `            colored = ${r.hasColored},`,
    `            plain = ${plain},`,
    `            color = ${r.color ? `${argb(r.color)}.toInt()` : 'null'},`,
    `            parts = mapOf(${partList}),`,
    `            label = "${r.label}",`,
    '        ),',
  ].join('\n');
};

writeFileSync(
  kotlin,
  `// Generated by android/ui/tools/gen-weather-icons.mjs. Do not edit by hand.
package dev.hashsome.ui

import dev.hashsome.client.WeatherCondition

/**
 * What each sky condition looks like (the web's \`CONDITION_LOOK\` and \`CONDITION_LABEL\`): its glyph, and the colors baked into it.
 * A glyph with a color of its own is drawn untinted; the parts with none are in a companion glyph, tinted with the text color.
 */
object WeatherLook {
    class Look(
        /** The glyph: the colored parts when there are any, else the plain ones. */
        val icon: Icon,
        /** Whether [icon] has its colors baked in and is drawn untinted. */
        val colored: Boolean,
        /** The parts that take the text color, drawn over [icon] tinted, when a colored glyph has some. */
        val plain: Icon?,
        /** The color of every part that [parts] does not name; null when the text color is used. */
        val color: Int?,
        /** Colors by shape (the i-th shape of the lucide icon). */
        val parts: Map<Int, Int>,
        val label: String,
    )

    val looks: Map<WeatherCondition, Look> = mapOf(
${rows.map(entry).join('\n')}
    )

    fun of(condition: WeatherCondition): Look = looks.getValue(condition)

    /** Whether [res] is a glyph drawn in its own colors. */
    internal fun isColored(res: Int): Boolean = looks.values.any { it.colored && it.icon.res == res }

    /** The companion that takes the text color for the glyph [res], if it has one. */
    internal fun plainOf(res: Int): Icon? = looks.values.firstOrNull { it.icon.res == res }?.plain
}
`,
);

console.log(`${rows.length} conditions`);

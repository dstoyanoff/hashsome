import { createElement } from 'react';
import styles from './icon.module.css';
import { ICON_NODES, type IconName, type IconNode } from './icon-data.ts';

export interface IconProps {
  /** Prefixed icon id, e.g. `lu:lightbulb`, `tb:vacuum-cleaner`. */
  name: IconName;

  /** Overrides the density's icon size, in px. */
  size?: number;

  /** For an icon drawn in more than one color: part `i` of the icon (its `i`th shape) is drawn in `colors[i]`, and any part without one, in the surrounding text color. Only for outline icons. */
  colors?: readonly (string | undefined)[];
}

const STROKE_WIDTH = 1.5;

/** Packs whose icons are drawn as outlines (`fill: none`, shapes stroked in). A pack not listed
 * here (e.g. a future filled pack) renders solid instead. */
const STROKE_PACKS = new Set(['lu', 'tb']);

/** The nodes of the icons drawn so far: each is parsed from its text once. */
const PARSED = new Map<string, readonly IconNode[]>();

function nodesOf(name: string): readonly IconNode[] | undefined {
  const known = PARSED.get(name);
  if (known) {
    return known;
  }

  const text = ICON_NODES[name];
  if (text === undefined) {
    return undefined;
  }

  const nodes = JSON.parse(text) as readonly IconNode[];
  PARSED.set(name, nodes);
  return nodes;
}

/** An inline SVG icon, from a plain prefixed id (`lu:` Lucide, `tb:` Tabler outline). Takes the surrounding text color. */
export function Icon({ name, size, colors }: IconProps) {
  const stroke = STROKE_PACKS.has(name.slice(0, name.indexOf(':')));
  const nodes = nodesOf(name);
  if (!nodes) {
    console.warn(`Could not find icon "${name}"`);
  }

  return (
    <svg
      viewBox="0 0 24 24"
      className={styles.icon}
      {...(size === undefined ? {} : { style: { width: size, height: size } })}
      fill={stroke ? 'none' : 'currentColor'}
      {...(stroke
        ? {
            stroke: 'currentColor',
            strokeWidth: STROKE_WIDTH,
            strokeLinecap: 'round',
            strokeLinejoin: 'round',
          }
        : {})}
      aria-hidden="true"
    >
      {(nodes ?? []).map(([tag, attrs], i) =>
        createElement(tag, {
          key: i,
          ...attrs,
          ...(stroke && colors?.[i] ? { stroke: colors[i] } : {}),
        }),
      )}
    </svg>
  );
}

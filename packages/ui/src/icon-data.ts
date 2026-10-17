// oxlint-disable-next-line typescript/triple-slash-reference
/// <reference path="./virtual-icons.d.ts" />
// The icons a bundle carries are only the ones its source names: `hashsomeIcons` (`@hashsome/ui/vite`) writes `virtual:hashsome-icons`
// from `icons.json`, the whole of Lucide and Tabler outline, at build time. See `src/vite/icons.ts`.
import { ICON_NODES as used } from 'virtual:hashsome-icons';

/** The icons this bundle uses, each as its nodes in one JSON string. */
export const ICON_NODES: Record<string, string> = used;

/** An SVG child element: its tag name and attributes, e.g. `['path', { d: '...' }]`. */
export type IconNode = readonly [tag: string, attrs: Record<string, string>];

/** An icon id, e.g. `'lu:lightbulb'`, `'tb:vacuum-cleaner'`. The pack prefix is checked, not every exact name (a literal union of
 * seven thousand names grinds the type checker to a halt): `Icon` warns at run time about an id that is not found. */
export type IconName = `lu:${string}` | `tb:${string}`;

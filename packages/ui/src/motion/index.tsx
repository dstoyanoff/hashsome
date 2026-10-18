import type { HTMLMotionProps, SVGMotionProps } from 'motion/react';
import {
  createElement,
  type ComponentType,
  type CSSProperties,
  type ReactElement,
  type ReactNode,
} from 'react';
import { motionLevel, useStill } from './mode.ts';

type Impl = typeof import('./impl.ts');

let impl: Impl | undefined;
let loading: Promise<Impl> | undefined;

/** Fetches the animation library, once. Resolves with it; `HashsomeProvider` waits for this before it draws in full motion. */
export function loadMotion(): Promise<Impl> {
  loading ??= import('./impl.ts').then((loaded) => {
    impl = loaded;
    loaded.MotionGlobalConfig.skipAnimations = motionLevel() !== 'full';
    return loaded;
  });

  return loading;
}

/** Whether the animation library has been fetched. */
export const motionLoaded = (): boolean => impl !== undefined;

/** The props that only the animation library understands. */
const MOTION_PROPS = new Set([
  'initial',
  'animate',
  'exit',
  'transition',
  'variants',
  'custom',
  'whileTap',
  'whileHover',
  'whileFocus',
  'whileDrag',
  'layout',
  'layoutId',
  'drag',
  'dragControls',
  'dragListener',
  'dragConstraints',
  'dragElastic',
  'onDragStart',
  'onDragEnd',
  'onAnimationComplete',
]);

const PX = new Set(['width', 'height', 'left', 'top', 'right', 'bottom']);

type Target = Record<string, unknown>;

/** Where an animation ends, as the style the element has once it is there: what a plain element draws in place of the motion one. */
function endStyle(
  animate: unknown,
  variants: Record<string, unknown> | undefined,
  custom: unknown,
): CSSProperties {
  let target = animate;
  if (typeof target === 'string') {
    const variant = variants?.[target];
    target = typeof variant === 'function' ? (variant as (c: unknown) => unknown)(custom) : variant;
  }

  if (typeof target !== 'object' || target === null) {
    return {};
  }

  const style: Record<string, unknown> = {};
  const transform: string[] = [];
  for (const [key, raw] of Object.entries(target as Target)) {
    if (key === 'transition') {
      continue;
    }

    // A keyframe list ends on its last frame.
    const value = Array.isArray(raw) ? raw.at(-1) : raw;
    if (key === 'x' || key === 'y') {
      transform.push(
        `translate${key.toUpperCase()}(${typeof value === 'number' ? `${value}px` : String(value)})`,
      );
    } else if (key === 'scale' || key === 'scaleX' || key === 'scaleY') {
      transform.push(`${key}(${String(value)})`);
    } else if (key === 'rotate') {
      transform.push(`rotate(${typeof value === 'number' ? `${value}deg` : String(value)})`);
    } else if (key === 'pathLength') {
      style['pathLength'] = value;
    } else {
      style[key] = typeof value === 'number' && PX.has(key) ? `${value}px` : value;
    }
  }

  if (transform.length > 0) {
    style['transform'] = transform.join(' ');
  }

  return style as CSSProperties;
}

function makeMotionTag<P extends object>(tag: string): ComponentType<P> {
  return function MotionTag(props: P) {
    const still = useStill();
    const all = props as Record<string, unknown>;
    if (!still && impl) {
      const Component = (impl.motion as unknown as Record<string, ComponentType<P>>)[tag]!;
      return createElement(Component, props);
    }

    const plain: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(all)) {
      if (!MOTION_PROPS.has(key)) {
        plain[key] = value;
      }
    }

    // Where it would have ended up, drawn at once; an element that animates from `initial` to nothing is just there.
    // An element with nothing to animate to stays as it starts.
    const end = endStyle(
      all['animate'] ?? (all['initial'] === false ? undefined : all['initial']),
      all['variants'] as Record<string, unknown> | undefined,
      all['custom'],
    );

    if (tag === 'path') {
      // A path that draws itself is just drawn.
      return createElement(tag, plain);
    }

    plain['style'] = { ...(all['style'] as CSSProperties | undefined), ...end };
    return createElement(tag, plain);
  } as ComponentType<P>;
}

/** The animation library's elements, which are plain elements while it is not in use (see `useStill`) and the library's own in full
 * motion. Used in place of `motion.div` and the like: a page that never animates then never fetches the library. */
/** A motion element's props, with a plain style (what the element has while it does not move). */
type Props<P> = Omit<P, 'style'> & { style?: CSSProperties | undefined };

export const m = {
  div: makeMotionTag<Props<HTMLMotionProps<'div'>>>('div'),
  span: makeMotionTag<Props<HTMLMotionProps<'span'>>>('span'),
  button: makeMotionTag<Props<HTMLMotionProps<'button'>>>('button'),
  li: makeMotionTag<Props<HTMLMotionProps<'li'>>>('li'),
  path: makeMotionTag<Props<SVGMotionProps<SVGPathElement>>>('path'),
};

export interface PresenceProps {
  children?: ReactNode;

  /** Whether what is there at the start animates in. Default `true`. */
  initial?: boolean;
  mode?: 'sync' | 'wait' | 'popLayout';
  custom?: unknown;
}

/** What lets an element that goes away animate out before it is removed: the library's `AnimatePresence` in full motion, and where
 * nothing moves just the children, which are removed at once. */
export function Presence(props: PresenceProps): ReactElement {
  const still = useStill();
  if (still || !impl) {
    return <>{props.children}</>;
  }

  return createElement(impl.AnimatePresence, props);
}

/** The library's `animate` for a value moved by hand, or `undefined` while it is not in use. */
export function motionAnimate(): Impl['animate'] | undefined {
  return impl?.animate;
}

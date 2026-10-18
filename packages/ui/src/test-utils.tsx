import { LocalClient, MockIntegration, type EntityInput, type MockLibrary } from '@hashsome/core';
import { act, cleanup, render, type RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach } from 'vitest';
import { resetMediaPending } from './entities/media-pending.ts';
import { HashsomeProvider, type MotionPreference } from './provider.tsx';

afterEach(() => {
  cleanup();
  resetMediaPending();
});

/** Renders `ui` against a mock backend and returns the backend for assertions. */
export function renderWithMock(
  ui: ReactElement,
  entities: Record<string, EntityInput> = {},
  options: { router?: boolean; library?: MockLibrary; motion?: MotionPreference } = {},
): RenderResult & { ha: MockIntegration; client: LocalClient } {
  const ha = new MockIntegration({
    entities,
    ...(options.library ? { library: options.library } : {}),
  });

  const client = new LocalClient([ha]);
  const tree = (
    <HashsomeProvider client={client} motion={options.motion ?? 'reduced'}>
      {options.router ? <MemoryRouter>{ui}</MemoryRouter> : ui}
    </HashsomeProvider>
  );

  return { ha, client, ...render(tree) };
}

/** Works a stylesheet value out as far as jsdom will not: `var(--x)` and `var(--x, fallback)` (the element's own inline custom
 * properties, then its ancestors', then the theme's), and `calc` / `max` over pixel lengths. */
export function resolveCss(element: Element, value: string): string {
  const root = getComputedStyle(document.documentElement);
  const lookup = (name: string): string => {
    for (let node: Element | null = element; node; node = node.parentElement) {
      const own = (node as HTMLElement).style?.getPropertyValue(name).trim();
      if (own) {
        return own;
      }
    }

    return root.getPropertyValue(name).trim();
  };

  let out = value;
  for (let round = 0; round < 6 && out.includes('var('); round++) {
    out = out.replace(
      /var\((--[\w-]+)\s*(?:,\s*([^()]*))?\)/g,
      (_, name: string, fallback?: string) => lookup(name) || (fallback ?? '').trim(),
    );
  }

  for (let round = 0; round < 8; round++) {
    const next = out.replace(
      /\b(calc|max)\(([^()]*)\)|(?<![\w-])\(([^()]*)\)/g,
      (whole, fn: string | undefined, body: string | undefined, bare: string | undefined) => {
        const result = evaluate(fn ?? 'calc', body ?? bare ?? '');
        return result === undefined ? whole : `${+result.toFixed(4)}px`;
      },
    );

    if (next === out) {
      break;
    }

    out = next;
  }

  return out;
}

/** A sum of pixel lengths with `+ - *`: undefined when it is not one. */
function sum(text: string): number | undefined {
  let total = 0;
  let sign = 1;
  const tokens = text.trim().split(/\s+/);
  let term: number | undefined;
  let pending: 'mul' | undefined;
  for (const token of tokens) {
    if (token === '+' || token === '-') {
      if (term === undefined) {
        return undefined;
      }

      total += sign * term;
      sign = token === '+' ? 1 : -1;
      term = undefined;
      continue;
    }

    if (token === '*') {
      pending = 'mul';
      continue;
    }

    const number = Number.parseFloat(token);
    if (Number.isNaN(number) || !/^-?[\d.]+(px)?$/.test(token)) {
      return undefined;
    }

    term = pending && term !== undefined ? term * number : number;
    pending = undefined;
  }

  return term === undefined ? undefined : total + sign * term;
}

/** A pixel-length expression with `+ - *` and, for `max`, comma-separated alternatives; undefined when it is not one. */
function evaluate(fn: string, body: string): number | undefined {
  if (fn === 'max') {
    const values = body.split(',').map(sum);
    return values.includes(undefined) ? undefined : Math.max(...(values as number[]));
  }

  return sum(body);
}

/** An element's `property` as the page's stylesheets give it, with the theme's custom properties and simple `calc`s worked out: the
 * inline style, else the last rule that matches it (jsdom does not work `var()` or `calc` out in `getComputedStyle`, and drops the
 * values it cannot). Not the cascade: the order the rules are in, which is enough for the stylesheets here. */
export function computed(element: Element, property: string): string {
  if (property.startsWith('--')) {
    return getComputedStyle(document.documentElement).getPropertyValue(property).trim();
  }

  const inline = (element as HTMLElement).style?.getPropertyValue(property);
  if (inline) {
    return resolveCss(element, inline);
  }

  let found = '';
  let best = -1;
  for (const sheet of document.styleSheets) {
    for (const rule of sheet.cssRules) {
      const { selectorText, style } = rule as CSSStyleRule;
      if (selectorText === undefined || selectorText === ':root') {
        continue;
      }

      try {
        if (element.matches(selectorText) && style.getPropertyValue(property)) {
          // The cascade, as far as the stylesheets here need: the more specific rule wins, the later one among equals.
          const weight = specificity(selectorText);
          if (weight >= best) {
            best = weight;
            found = style.getPropertyValue(property);
          }
        }
      } catch {
        // A selector jsdom cannot match (a pseudo-class, say) is not the element's own.
      }
    }
  }

  if (found) {
    return resolveCss(element, found);
  }

  const style = getComputedStyle(element);
  return resolveCss(
    element,
    style.getPropertyValue(property) ||
      (style as unknown as Record<string, string>)[property] ||
      '',
  );
}

/** A selector's specificity as one number (ids, then classes / attributes / pseudo-classes, then elements); `:where()` counts for nothing. */
function specificity(selector: string): number {
  const bare = selector.replace(/:where\([^)]*\)/g, '');
  const ids = (bare.match(/#[\w-]+/g) ?? []).length;
  const classes = (bare.match(/\.[\w-]+|\[[^\]]*\]|:(?!:)[\w-]+/g) ?? []).length;
  const types = (bare.match(/(^|[\s>+~])[a-z][\w-]*/gi) ?? []).length;
  return ids * 10_000 + classes * 100 + types;
}

/** Lets what is waiting on a module being fetched (`lazyExport`) and the promises behind it finish, under fake timers too. */
export async function settle(): Promise<void> {
  for (let round = 0; round < 5; round++) {
    await act(async () => {});
  }
}

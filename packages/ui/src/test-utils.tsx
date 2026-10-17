import { LocalClient, MockIntegration, type EntityInput, type MockLibrary } from '@hashsome/core';
import { cleanup, render, type RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { afterEach } from 'vitest';
import { resetMediaPending } from './entities/media-pending.ts';
import { HashsomeProvider } from './provider.tsx';

afterEach(() => {
  cleanup();
  resetMediaPending();
});

/** Renders `ui` against a mock backend and returns the backend for assertions. */
export function renderWithMock(
  ui: ReactElement,
  entities: Record<string, EntityInput> = {},
  options: { router?: boolean; library?: MockLibrary } = {},
): RenderResult & { ha: MockIntegration; client: LocalClient } {
  const ha = new MockIntegration({
    entities,
    ...(options.library ? { library: options.library } : {}),
  });

  const client = new LocalClient([ha]);
  const tree = (
    <HashsomeProvider client={client}>
      {options.router ? <MemoryRouter>{ui}</MemoryRouter> : ui}
    </HashsomeProvider>
  );

  return { ha, client, ...render(tree) };
}

/** A CSS value as it comes to once the theme's custom properties are in it (`var(--hs-bg)` as `#0B0A0D`, `calc(var(--hs-unit) * 2)`
 * as `8px`): jsdom keeps `var()` as it was written and does not work out `calc`. */
export function resolveCss(element: Element, value: string): string {
  const style = getComputedStyle(document.documentElement);
  let out = value;
  for (let round = 0; round < 4 && out.includes('var('); round++) {
    out = out.replace(/var\((--[\w-]+)\)/g, (_, name: string) =>
      style.getPropertyValue(name).trim(),
    );
  }

  // `calc(4px * 2)`, the only shape the stylesheets use.
  out = out.replace(
    /calc\(\s*(-?[\d.]+)px\s*\*\s*(-?[\d.]+)\s*\)/g,
    (_, a: string, b: string) => `${+(Number(a) * Number(b)).toFixed(4)}px`,
  );

  void element;
  return out;
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
  for (const sheet of document.styleSheets) {
    for (const rule of sheet.cssRules) {
      const { selectorText, style } = rule as CSSStyleRule;
      if (selectorText === undefined || selectorText === ':root') {
        continue;
      }

      try {
        if (element.matches(selectorText) && style.getPropertyValue(property)) {
          found = style.getPropertyValue(property);
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

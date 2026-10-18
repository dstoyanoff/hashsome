// @vitest-environment node
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { expect, test } from 'vitest';
import * as ui from '../../index.ts';
import { COMPONENT_PROPS } from '../props-data.ts';

test('every component the package exports has a live demo in the gallery', () => {
  const gallery = readFileSync(fileURLToPath(new URL('../gallery.tsx', import.meta.url)), 'utf8');
  const components = Object.entries(ui)
    .filter(([name, value]) => typeof value === 'function' && /^[A-Z][a-z]/.test(name))
    .map(([name]) => name);

  const missing = components.filter((name) => !gallery.includes(`<${name}`));
  expect(missing).toEqual([]);
});

test('every exported component documents itself and each of its props, and the gallery lists them', () => {
  const gallery = readFileSync(fileURLToPath(new URL('../gallery.tsx', import.meta.url)), 'utf8');
  const components = Object.entries(ui)
    .filter(([name, value]) => typeof value === 'function' && /^[A-Z][a-z]/.test(name))
    .map(([name]) => name);

  const missingEntry = components.filter((name) => !COMPONENT_PROPS[name]);
  const undocumentedComponent = components.filter((name) => !COMPONENT_PROPS[name]?.doc);
  const undocumentedProps = components.flatMap((name) =>
    (COMPONENT_PROPS[name]?.props ?? [])
      .filter((prop) => !prop.doc)
      .map((prop) => `${name}.${prop.name}`),
  );

  const notListed = components.filter((name) => !new RegExp(`['"]${name}['"]`).test(gallery));

  expect({ missingEntry, undocumentedComponent, undocumentedProps, notListed }).toEqual({
    missingEntry: [],
    undocumentedComponent: [],
    undocumentedProps: [],
    notListed: [],
  });
});

test('components are styled only through their stylesheets: no `className` or `style` props', () => {
  const offenders = Object.entries(COMPONENT_PROPS).flatMap(([name, entry]) =>
    entry.props
      .filter((p) => p.name === 'className' || p.name === 'style')
      .map((p) => `${name}.${p.name}`),
  );

  expect(offenders).toEqual([]);
});

import { act, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Page } from '../page.tsx';
import { RoomHeader } from '../room-header.tsx';

beforeEach(() => localStorage.clear());
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  localStorage.clear();
  window.history.replaceState({}, '', '/');
});

test('a page draws no grid unless the debug menu turned it on', () => {
  renderWithMock(
    <Page>
      <RoomHeader title="Kitchen" />
    </Page>,
    {},
  );

  expect(document.querySelector('[data-grid-overlay]')).toBeNull();
});

test("the debug menu's grid draws the grid over the page, boxes each card and says how many are off the grid", async () => {
  localStorage.setItem('hashsome:grid', 'on');
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (
    this: HTMLElement,
  ) {
    // The header is a clean 32px at the page's origin; the page itself is 400 x 300.
    return this.tagName === 'HEADER'
      ? ({ left: 12, top: 12, width: 100, height: 32 } as DOMRect)
      : ({ left: 0, top: 0, width: 400, height: 300 } as DOMRect);
  });

  renderWithMock(
    <Page>
      <RoomHeader title="Kitchen" />
    </Page>,
    {},
  );

  await act(() => new Promise((done) => requestAnimationFrame(() => done(undefined))));
  expect(document.querySelector('[data-grid-overlay]')).not.toBeNull();
  expect(document.body.textContent).toContain('1u = 4px');
  expect(document.body.textContent).toContain('all on grid');
  // The one card is boxed, 32px = 8 modules tall.
  expect(document.body.textContent).toContain('8u');
});

test('the overlay is hidden from assistive technology', async () => {
  localStorage.setItem('hashsome:grid', 'on');
  renderWithMock(<Page>{null}</Page>, {});
  await act(() => new Promise((done) => requestAnimationFrame(() => done(undefined))));
  expect(document.querySelector('[data-grid-overlay]')?.getAttribute('aria-hidden')).toBe('true');
});

test('the page pads its top and bottom with half of what its height has over whole modules each', () => {
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(459);
  renderWithMock(<Page>{null}</Page>, {});
  const main = document.querySelector('main') as HTMLElement;
  // 459 - 2 x 12 = 435 = 108 modules + 3px: 1px more on top, 2px more below.
  expect(main.dataset['centered']).toBe('1 2');
  expect(computed(main, 'padding-top')).toBe('13px');
  expect(computed(main, 'padding-bottom')).toBe('14px');
});

test('the overlay is not a box in the page, so the page keeps its gaps and its height with it on', async () => {
  localStorage.setItem('hashsome:grid', 'on');
  renderWithMock(<Page>{null}</Page>, {});
  await act(() => new Promise((done) => requestAnimationFrame(() => done(undefined))));
  // A box here would be one more item in the page's flex column, and one more gap.
  expect(
    getComputedStyle(document.querySelector('[data-grid-overlay]') as HTMLElement).display,
  ).toBe('contents');
});

test('the legend says what the device is: its viewport, its screen and its pixel ratio', async () => {
  localStorage.setItem('hashsome:grid', 'on');
  vi.stubGlobal('innerWidth', 1000);
  vi.stubGlobal('innerHeight', 600);
  vi.stubGlobal('devicePixelRatio', 1.6);
  vi.stubGlobal('screen', { width: 1280, height: 800 });
  renderWithMock(<Page>{null}</Page>, {});
  await act(() => new Promise((done) => requestAnimationFrame(() => done(undefined))));

  expect(document.body.textContent).toContain('viewport 1000×600 · screen 1280×800 @1.6');
  vi.unstubAllGlobals();
});

// @vitest-environment jsdom
import { act, cleanup, render } from '@testing-library/react';
import { useRef } from 'react';
import { afterEach, expect, test, vi } from 'vitest';
import { useGlide } from '../use-glide.ts';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function Row({ selected }: { selected: 'a' | 'b' }) {
  const row = useRef<HTMLDivElement>(null);
  useGlide(row, selected);
  return (
    <div ref={row} data-testid="row">
      <span data-selected={selected === 'a'}>a</span>
      <span data-selected={selected === 'b'}>b</span>
    </div>
  );
}

function stubLayout() {
  const widths = new Map<string, number>();
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return widths.get(this.textContent ?? '') ?? 0;
  });

  const watchers: Array<() => void> = [];
  vi.stubGlobal(
    'ResizeObserver',
    class {
      readonly #callback: () => void;
      constructor(callback: () => void) {
        this.#callback = callback;
        watchers.push(callback);
      }

      observe() {}
      disconnect() {
        watchers.splice(watchers.indexOf(this.#callback), 1);
      }
    },
  );

  return { widths, resized: () => watchers.forEach((watch) => watch()) };
}

test('the marker is put in place without a glide, and glides only when the selection changes', () => {
  const { widths } = stubLayout();
  widths.set('a', 100);
  widths.set('b', 140);
  const { getByTestId, rerender } = render(<Row selected="a" />);
  const row = getByTestId('row');
  expect(row.style.getPropertyValue('--glide-n')).toBe('100');
  expect(row.dataset['glide']).toBeUndefined();

  rerender(<Row selected="b" />);
  expect(row.style.getPropertyValue('--glide-n')).toBe('140');
  expect(row.dataset['glide']).toBe('on');
});

test('a row that is laid out late puts the marker in its place with no glide, from wherever it was measured first', () => {
  const { widths, resized } = stubLayout();
  const { getByTestId } = render(<Row selected="a" />);
  const row = getByTestId('row');
  // Nothing was laid out at first: it measured nothing.
  expect(row.style.getPropertyValue('--glide-n')).toBe('0');

  widths.set('a', 120);
  act(() => resized());
  expect(row.style.getPropertyValue('--glide-n')).toBe('120');
  expect(row.dataset['glide']).toBeUndefined();
});

test('the observer reporting what is already placed leaves a running glide alone', () => {
  const { widths, resized } = stubLayout();
  widths.set('a', 100);
  widths.set('b', 140);
  const { getByTestId, rerender } = render(<Row selected="a" />);
  const row = getByTestId('row');
  rerender(<Row selected="b" />);
  act(() => resized());
  expect(row.dataset['glide']).toBe('on');
});

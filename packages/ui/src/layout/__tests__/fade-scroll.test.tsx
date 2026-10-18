// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { LocalClient } from '@hashsome/core';
import { afterEach, expect, test } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { FadeScroll } from '../fade-scroll.tsx';

afterEach(() => {
  cleanup();
  for (const name of ['scrollWidth', 'clientWidth', 'scrollLeft'] as const) {
    delete (HTMLElement.prototype as unknown as Record<string, unknown>)[name];
  }
});

/** jsdom lays nothing out, so say how wide the row is, how much it holds and how far it is scrolled. */
function metrics(scrollWidth: number, clientWidth: number, scrollLeft = 0) {
  for (const [name, value] of Object.entries({ scrollWidth, clientWidth })) {
    Object.defineProperty(HTMLElement.prototype, name, { configurable: true, value });
  }

  Object.defineProperty(HTMLElement.prototype, 'scrollLeft', {
    configurable: true,
    writable: true,
    value: scrollLeft,
  });
}

const fading = () => {
  const row = screen.getByTestId('row');
  return [row.dataset.fadeStart, row.dataset.fadeEnd];
};

test('a row with more beyond its right edge fades out there, and nowhere when it all fits', () => {
  metrics(500, 200);
  const { unmount } = render(<FadeScroll data-testid="row">content</FadeScroll>);
  expect(fading()).toEqual(['false', 'true']);
  unmount();

  metrics(200, 200);
  render(<FadeScroll data-testid="row">content</FadeScroll>);
  expect(fading()).toEqual(['false', 'false']);
});

test('scrolled, it fades on the left too, and only on the left at the far end', () => {
  metrics(500, 200);
  render(<FadeScroll data-testid="row">content</FadeScroll>);
  const row = screen.getByTestId('row');

  act(() => {
    row.scrollLeft = 100;
    fireEvent.scroll(row);
  });

  expect(fading()).toEqual(['true', 'true']);

  act(() => {
    row.scrollLeft = 300;
    fireEvent.scroll(row);
  });

  expect(fading()).toEqual(['true', 'false']);
});

test('it scrolls sideways and takes the element’s props', () => {
  metrics(200, 200);
  render(
    <HashsomeProvider client={new LocalClient([])}>
      <FadeScroll as="ul" data-testid="row">
        <li>a</li>
      </FadeScroll>
    </HashsomeProvider>,
  );

  const row = screen.getByTestId('row');
  expect(row.tagName).toBe('UL');
  expect(getComputedStyle(row).overflowX).toBe('auto');
});

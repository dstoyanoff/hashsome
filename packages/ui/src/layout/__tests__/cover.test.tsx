import { cleanup, render } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { Cover } from '../cover.tsx';

afterEach(cleanup);

test('a picture is fetched when it nears the screen, unless it is on the screen as the page opens', () => {
  const { container, rerender } = render(<Cover src="https://example.com/a.jpg" size="small" />);
  const image = () => container.querySelector('img')!;
  expect(image().getAttribute('loading')).toBe('lazy');
  expect(image().getAttribute('fetchpriority')).toBeNull();

  rerender(<Cover src="https://example.com/a.jpg" size="small" priority />);
  expect(image().getAttribute('loading')).toBe('eager');
  expect(image().getAttribute('fetchpriority')).toBe('high');
});

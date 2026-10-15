import { assetUrl } from '@hashsome/core';
import { render } from '@testing-library/react';
import { expect, test } from 'vitest';
import { Cover } from '../cover.tsx';

const srcOf = (container: HTMLElement) => container.querySelector('img')?.getAttribute('src');
const url = assetUrl('ma', '/imageproxy/abc?size=256&fmt=jpg');

test('a picture is asked for at the size it is given, in words', () => {
  expect(srcOf(render(<Cover src={url} size="small" />).container)).toBe(`${url}&size=small`);
  expect(srcOf(render(<Cover src={url} size="medium" />).container)).toBe(`${url}&size=medium`);
  expect(srcOf(render(<Cover src={url} size="large" />).container)).toBe(`${url}&size=large`);
});

test('a picture from anywhere else is used as it is', () => {
  const { container } = render(<Cover src="https://i.scdn.co/image/xyz" size="small" />);
  expect(srcOf(container)).toBe('https://i.scdn.co/image/xyz');
});

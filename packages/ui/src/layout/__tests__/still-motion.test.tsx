import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MotionGlobalConfig } from 'motion/react';
import { MemoryRouter, Link, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, test } from 'vitest';
import { AnimatedOutlet } from '../animated-outlet.tsx';
import { Reveal } from '../reveal.tsx';
import { IconButton } from '../tile.tsx';
import { HashsomeProvider } from '../../provider.tsx';
import { LocalClient, MockIntegration } from '@hashsome/core';

// `?motion=reduced` is not the device's own setting: the app turns the animations off for itself, and a component
// must then draw no motion element at all, not one with its animation skipped.
beforeEach(() => {
  MotionGlobalConfig.skipAnimations = true;
});

afterEach(() => {
  cleanup();
  MotionGlobalConfig.skipAnimations = false;
});

test('a revealed card is just there, with nothing around it', () => {
  const { container } = render(
    <Reveal>
      <span>card</span>
    </Reveal>,
  );

  expect((container.firstElementChild as HTMLElement).tagName).toBe('SPAN');
});

test('past the first handful, a card is not revealed even where motion is on', () => {
  MotionGlobalConfig.skipAnimations = false;
  const { container } = render(
    <>
      <Reveal index={0}>
        <span>first</span>
      </Reveal>
      <Reveal index={40}>
        <span>fortieth</span>
      </Reveal>
    </>,
  );

  expect(screen.getByText('first').parentElement).not.toBe(container);
  expect(screen.getByText('fortieth').parentElement).toBe(container);
});

test('a page is there as soon as it is navigated to, with no page leaving first', () => {
  render(
    <MemoryRouter initialEntries={['/room']}>
      <Link to="/room/two">to two</Link>
      <Routes>
        <Route path="/room" element={<AnimatedOutlet />}>
          <Route index element={<p>page one</p>} />
          <Route path="two" element={<p>page two</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );

  fireEvent.click(screen.getByText('to two'));
  expect(screen.queryByText('page one')).toBeNull();
  expect(screen.getByText('page two')).toBeTruthy();
});

test('an icon button is a plain button, and still shows the icon it is given next', () => {
  const client = new LocalClient([new MockIntegration({})]);
  const button = (icon: 'lu:play' | 'lu:pause') => (
    <HashsomeProvider client={client} theme="dark">
      <IconButton icon={icon} label="Play" />
    </HashsomeProvider>
  );

  const { rerender } = render(button('lu:play'));
  const first = screen.getByRole('button', { name: 'Play' });
  expect(first.style.transform).toBe('');
  rerender(button('lu:pause'));
  expect(screen.getByRole('button', { name: 'Play' }).querySelector('svg')).toBeTruthy();
});

// @vitest-environment jsdom
import { LocalClient, MockIntegration } from '@hashsome/core';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { HashsomeProvider } from '../../provider.tsx';
import { useDetail } from '../detail-provider.tsx';

afterEach(() => {
  cleanup();
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
});

const setWidth = (width: number) =>
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });

const TITLE = <b>Thing</b>;
const BODY = <p>what is in it</p>;

function Opener({ expanded = false }: { expanded?: boolean }) {
  const { openDetail } = useDetail();
  return (
    <button type="button" onClick={() => openDetail('thing', TITLE, BODY, expanded)}>
      open
    </button>
  );
}

const show = (expanded?: boolean) =>
  render(
    <HashsomeProvider client={new LocalClient([new MockIntegration({})])} theme="dark">
      <Opener {...(expanded === undefined ? {} : { expanded })} />
    </HashsomeProvider>,
  );

test('on a wide screen the drawer is a panel at the side, which can be expanded', async () => {
  setWidth(1024);
  show();
  fireEvent.click(screen.getByText('open'));
  expect(await screen.findByRole('dialog')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Expand' })).toBeTruthy();
});

test('on a phone it is a sheet from the bottom: a grip, the content, a close button, and nothing to expand', async () => {
  setWidth(390);
  show();
  fireEvent.click(screen.getByText('open'));
  const dialog = await screen.findByRole('dialog');
  expect(screen.queryByRole('button', { name: 'Expand' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Collapse' })).toBeNull();
  expect(dialog.textContent).toContain('what is in it');
  expect(dialog.style.borderRadius === '' || dialog.getAttribute('class')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  await new Promise((resolve) => setTimeout(resolve, 600));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a sheet opened tall is tall, and Escape lets it go', async () => {
  setWidth(390);
  show(true);
  fireEvent.click(screen.getByText('open'));
  const dialog = await screen.findByRole('dialog');
  expect(getComputedStyle(dialog).height).toBe('92dvh');
  fireEvent.keyDown(window, { key: 'Escape' });
  await new Promise((resolve) => setTimeout(resolve, 600));
  expect(screen.queryByRole('dialog')).toBeNull();
});

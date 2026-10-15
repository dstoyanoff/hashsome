import { act, cleanup, fireEvent, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { renderWithMock } from '../../test-utils.tsx';
import { TaskTile, taskLine } from '../task-tile.tsx';

afterEach(cleanup);

const NOW = new Date(2026, 9, 9, 14, 30);
const days = (n: number) => new Date(2026, 9, 9 + n, 0, 0);

test('the line says how far the task is, in days, whatever the hour', () => {
  expect(taskLine('ok', days(7), NOW)).toMatch(/^Next /);
  expect(taskLine('dueSoon', days(2), NOW)).toBe('Due in 2 days');
  expect(taskLine('dueSoon', days(1), NOW)).toBe('Due tomorrow');
  expect(taskLine('dueSoon', days(0), NOW)).toBe('Due today');
  expect(taskLine('overdue', days(0), NOW)).toBe('Due today');
  expect(taskLine('overdue', days(-1), NOW)).toBe('Overdue by 1 day');
  expect(taskLine('overdue', days(-3), NOW)).toBe('Overdue by 3 days');
  expect(taskLine('overdue', undefined, NOW)).toBe('Overdue');
});

const show = (
  onComplete = vi.fn<(done: Date) => void | Promise<void>>(),
  state: 'ok' | 'dueSoon' | 'overdue' = 'dueSoon',
) => {
  renderWithMock(
    <TaskTile
      label="Septic additive"
      state={state}
      dueAt={new Date(Date.now() + 2 * 86_400_000)}
      lastDoneAt={new Date(2026, 9, 2)}
      onComplete={onComplete}
    />,
    {},
  );

  return onComplete;
};

test('the year is only there when the day is in another year', () => {
  const sameYear = new Date(NOW.getFullYear(), 11, 31);
  const later = new Date(NOW.getFullYear() + 1, 2, 10);
  expect(taskLine('ok', sameYear, NOW)).not.toContain(String(NOW.getFullYear()));
  expect(taskLine('ok', later, NOW)).toContain(String(NOW.getFullYear() + 1));
});

test('a tap only asks: nothing is done until it is confirmed, and backing out does nothing', () => {
  const onComplete = show();
  expect(screen.queryByRole('dialog')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: /Septic additive/ }));
  expect(screen.getByRole('dialog', { name: 'Mark “Septic additive” as done?' })).toBeTruthy();
  expect(screen.getByRole('dialog').textContent).toContain('Last done');
  fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onComplete).not.toHaveBeenCalled();
});

test('confirming says it was done today, and closes', async () => {
  const onComplete = show();
  fireEvent.click(screen.getByRole('button', { name: /Septic additive/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Mark as done' }));
  await waitFor(() => expect(onComplete).toHaveBeenCalledTimes(1));
  const done = onComplete.mock.calls[0]![0] as Date;
  expect(done.toDateString()).toBe(new Date().toDateString());
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('an earlier day can be stepped back to, for a task that was forgotten at the time, but not a later one', async () => {
  const onComplete = show();
  fireEvent.click(screen.getByRole('button', { name: /Septic additive/ }));
  expect(screen.getByText('Today')).toBeTruthy();
  expect((screen.getByRole('button', { name: 'Next day' }) as HTMLButtonElement).disabled).toBe(
    true,
  );

  fireEvent.click(screen.getByRole('button', { name: 'Previous day' }));
  expect(screen.getByText('Yesterday')).toBeTruthy();
  fireEvent.click(screen.getByRole('button', { name: 'Previous day' }));
  fireEvent.click(screen.getByRole('button', { name: 'Next day' }));
  fireEvent.click(screen.getByRole('button', { name: 'Mark as done' }));
  await waitFor(() => expect(onComplete).toHaveBeenCalledTimes(1));
  const yesterday = new Date(Date.now() - 86_400_000);
  expect((onComplete.mock.calls[0]![0] as Date).toDateString()).toBe(yesterday.toDateString());
});

test('a failure is shown in the dialog, which stays open to try again', async () => {
  const onComplete = vi
    .fn<(done: Date) => Promise<void>>()
    .mockRejectedValue(new Error('No connection'));

  show(onComplete, 'overdue');
  fireEvent.click(screen.getByRole('button', { name: /Septic additive/ }));
  await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Mark as done' })));
  expect((await screen.findByRole('alert')).textContent).toBe('No connection');
  expect(screen.getByRole('dialog')).toBeTruthy();
  expect((screen.getByRole('button', { name: 'Mark as done' }) as HTMLButtonElement).disabled).toBe(
    false,
  );
});

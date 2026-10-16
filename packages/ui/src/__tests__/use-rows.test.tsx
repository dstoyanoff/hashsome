import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test } from 'vitest';
import { FIRST_ROWS, useRows } from '../use-rows.ts';

afterEach(cleanup);

const ITEMS = Array.from({ length: 60 }, (_, i) => `row ${i}`);

function List({ items, listKey }: { items: string[]; listKey: string }) {
  const { shown, attach, onScroll } = useRows(items, listKey);
  return (
    <ul ref={attach} onScroll={onScroll}>
      {shown.map((item) => (
        <li key={item}>{item}</li>
      ))}
    </ul>
  );
}

test('a long list is drawn a screenful at a time, not all at once', () => {
  render(<List items={ITEMS} listKey="a" />);
  expect(screen.getAllByRole('listitem')).toHaveLength(FIRST_ROWS);
});

test('a list with room to spare gets the rest, since there is no scrolling to ask for it', async () => {
  // jsdom lays nothing out: the list has no height, so the end of it is always near.
  render(<List items={ITEMS} listKey="a" />);
  await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(ITEMS.length));
});

test('a short list is drawn whole', () => {
  render(<List items={ITEMS.slice(0, 4)} listKey="a" />);
  expect(screen.getAllByRole('listitem')).toHaveLength(4);
});

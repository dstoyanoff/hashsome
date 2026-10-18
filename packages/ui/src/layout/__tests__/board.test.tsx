import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { computed, renderWithMock } from '../../test-utils.tsx';
import { Board, Cell } from '../board.tsx';
import { TopRow } from '../top-row.tsx';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/** The board's stylesheet as text: jsdom does not know grid properties, so what the board asks for is read from the file. */
const css = () => readFileSync(join(import.meta.dirname, '../board.module.css'), 'utf8');

test('a board is columns with a gap between them and rows as tall as their content', () => {
  renderWithMock(
    <Board columns={6}>
      <Cell>one</Cell>
    </Board>,
    {},
  );

  const board = document.querySelector('[data-board]') as HTMLElement;
  expect(board).not.toBeNull();
  expect(board.style.getPropertyValue('--columns')).toBe('6');
  expect(css()).toContain('repeat(var(--columns), minmax(0, 1fr))');
  expect(css()).toContain('max-content');
});

test('a cell says only how big it is: columns wide, rows tall, and all columns by default', () => {
  renderWithMock(
    <Board>
      <Cell>everything</Cell>
      <Cell cols={8}>a room</Cell>
      <Cell cols={4} rows={3}>
        a player
      </Cell>
    </Board>,
    {},
  );

  const [everything, room, player] = [...document.querySelectorAll<HTMLElement>('[data-cell]')];
  expect(everything?.style.gridColumn).toBe('1 / -1');
  expect(room?.style.gridColumn).toBe('span 8');
  expect(player?.style.gridColumn).toBe('span 4');
  expect(player?.style.gridRow).toBe('span 3');
  // A cell that reaches across rows stretches; one that does not takes the height of its content.
  expect(css()).toContain('align-self: stretch');
  expect(css()).toContain('align-self: start');
});

test('a filling cell goes from where it starts to the bottom of the page, whatever rows are beside it', () => {
  let page = 400;
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return this.hasAttribute('data-board') ? page : 0;
  });

  // The room is 300 tall from the top and the page 400; the player starts at 180, under the top row,
  // wherever the rows beside it end.
  const isPlayer = (element: HTMLElement) => element.textContent === 'player';
  vi.spyOn(HTMLElement.prototype, 'offsetTop', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return isPlayer(this) ? 180 : 0;
  });

  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function (
    this: HTMLElement,
  ) {
    return isPlayer(this) ? 120 : 300;
  });

  const view = renderWithMock(
    <Board>
      <Cell>room</Cell>
      <Cell rows="fill">player</Cell>
    </Board>,
    {},
  );

  const player = () =>
    [...document.querySelectorAll('[data-cell]')].find(
      (cell) => cell.textContent === 'player',
    ) as HTMLElement;

  // The card is drawn in the cell, outside the rows' sizing (a height on the cell would grow its row).
  const drawn = () => player().firstElementChild as HTMLElement;

  // From 180 to the page's 400: 220, and it stays there.
  expect(getComputedStyle(drawn()).height).toBe('220px');
  expect(css()).toContain('align-self: stretch');

  // A shorter page: to 350, so 170.
  view.unmount();
  page = 350;
  renderWithMock(
    <Board>
      <Cell>room</Cell>
      <Cell rows="fill">player</Cell>
    </Board>,
    {},
  );

  expect(getComputedStyle(drawn()).height).toBe('170px');
});

test('a top row is the height the grid gives the top row, with its pieces at the right', () => {
  renderWithMock(
    <TopRow>
      <span>clock</span>
    </TopRow>,
    {},
  );

  const row = document.querySelector('span')?.parentElement as HTMLElement;
  expect(computed(row, 'min-height')).toBe('40px');
  expect(getComputedStyle(row).justifyContent).toBe('flex-end');
});

/** @jsxImportSource @emotion/react */
import { Box } from 'e-prim';
import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';

export interface BoardProps {
  /** How many equal columns the page is divided into. Default 12. */
  columns?: number;

  /** `Cell`s, each saying how big it is; the board places them. */
  children: ReactNode;
}

/** A page laid out on a grid, for a dashboard made for one device. The page is `columns` equal columns
 * (a gap between them) and rows as tall as what is in them, with twice that gap between rows. Nothing
 * says where a cell goes: each `Cell` only says how big it is, and the board puts it in the first
 * place it fits, in the order written. Every card is a whole number of grid modules tall and every gap
 * is 3, so whatever the board makes stays on the grid. It takes the height that is left of the page.
 * Opt in: a page of plain flex columns keeps working the same. */
export function Board({ columns = 12, children }: BoardProps) {
  return (
    <Box
      data-board
      position="relative"
      css={({ density }) => ({
        display: 'grid',
        gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
        gridAutoRows: 'max-content',
        alignContent: 'start',
        // Twice as far between rows as between columns and inside a cell: a row is a group (a room), and
        // groups read as groups.
        columnGap: density.space,
        rowGap: density.space * 2,
        // The height of its rows, or the page's if that is more: it grows into what is left, and never
        // shrinks below its content (the page scrolls instead).
        flex: '1 0 auto',
        minWidth: 0,
      })}
    >
      {children}
    </Box>
  );
}

export interface CellProps {
  /** How many columns wide. Default: all of them. */
  cols?: number;

  /** How many rows of the board it covers, counting rows as tall as their content (a room is one): `3` ends where the third row does, and the cell is as tall as those rows, whatever is in it (a card in it that is too tall squeezes). `'fill'` starts where the cell is placed and goes down to the bottom of the page (or of the content, if that is longer), whatever rows are beside it; they keep their place. Default 1. */
  rows?: number | 'fill';

  /** What is in it, one under the other with the gap between. In a cell of more than one row, or `'fill'`, the last card stretches to the cell's height. */
  children: ReactNode;
}

/** Something on a `Board`, and how big it is: `cols` wide and `rows` tall. Where it goes is the board's
 * business. A room is one cell (its header and its tiles), a player beside three of them is one that
 * covers three rows, or fills. */
export function Cell({ cols, rows = 1, children }: CellProps) {
  const ref = useRef<HTMLDivElement>(null);
  const fill = rows === 'fill';
  const [height, setHeight] = useState(0);

  // `fill`: as tall as from where the cell starts to the bottom of the board, or to the end of the
  // others' content if that is further. The others' content, not its own: it is what is being set.
  useLayoutEffect(() => {
    const cell = ref.current;
    const board = cell?.parentElement;
    if (!fill || !cell || !board) {
      return;
    }

    const measure = () => {
      const others = [...board.children].filter((child) => child !== cell) as HTMLElement[];
      const bottom = Math.max(
        board.clientHeight,
        ...others.map((child) => child.offsetTop + child.offsetHeight),
      );

      const next = Math.max(0, Math.floor(bottom - cell.offsetTop));
      setHeight((current) => (Math.abs(current - next) < 1 ? current : next));
    };

    measure();
    if (typeof ResizeObserver === 'undefined') {
      return;
    }

    const observer = new ResizeObserver(measure);
    observer.observe(board);
    for (const child of board.children) {
      observer.observe(child);
    }

    return () => observer.disconnect();
  }, [fill]);

  const span = typeof rows === 'number' ? rows : 1;
  const stretches = fill || span > 1;
  const column = (gap: number) => ({
    display: 'flex',
    flexDirection: 'column' as const,
    gap,
    '& > [data-grid-card]:last-child': stretches ? { flexGrow: 1 } : undefined,
  });

  return (
    <Box
      ref={ref}
      data-cell
      css={({ density }) => ({
        gridColumn: cols === undefined ? '1 / -1' : `span ${cols}`,
        gridRow: span > 1 ? `span ${span}` : undefined,
        minWidth: 0,
        // A cell takes the height of what is in it, unless it is meant to reach across rows.
        alignSelf: stretches ? 'stretch' : 'start',
        // One that reaches across rows, or fills, takes the height the page and the others give it; its
        // own content does not size them (a player taller than three rooms squeezes, it does not
        // stretch the third, or push the page past its bottom).
        contain: stretches ? 'size' : undefined,
        ...(fill ? { position: 'relative' as const } : column(density.space)),
      })}
    >
      {fill ? (
        // The card is drawn from the cell's top to the bottom of the board, outside the rows' sizing:
        // an explicit height on the cell itself would make the row grow to it.
        <Box
          position="absolute"
          css={({ density }) => ({
            top: 0,
            left: 0,
            right: 0,
            height: height > 0 ? height : '100%',
            ...column(density.space),
          })}
        >
          {children}
        </Box>
      ) : (
        children
      )}
    </Box>
  );
}

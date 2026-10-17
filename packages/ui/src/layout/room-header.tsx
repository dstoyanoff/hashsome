/** @jsxImportSource @emotion/react */
import { Box, Flex, Typography } from '../style/prim.tsx';
import type { ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';

export interface RoomHeaderProps {
  /** The room's name. */
  title: string;

  /** Icon id, e.g. `lu:sofa`. */
  icon?: IconName;

  /** Header readouts, typically `<SensorReadout />`s. */
  readouts?: ReactNode;
}

/** The header that starts a room: muted icon and title, a hairline, and readouts on the right. Put it above a `Grid` (or any tiles); it adds its own space above, so consecutive rooms read as groups. */
export function RoomHeader({ title, icon, readouts }: RoomHeaderProps) {
  return (
    <Flex
      as="header"
      data-grid-card
      align="center"
      color="textMuted"
      minHeight={32}
      mt={3}
      gap={3}
      // A header starts a new group: the extra space sits on top of the screen's own gap, so a
      // room reads as header + its tiles rather than as evenly spaced rows.
      css={{ '&:first-child': { marginTop: 0 } }}
    >
      {icon ? <Icon name={icon} size={16} /> : null}
      <Typography as="h2" variant="roomTitle" color="textMuted" css={{ margin: 0 }}>
        {title}
      </Typography>
      <Box as="span" background="border" css={{ flex: 1, height: 1 }} />
      {readouts ? (
        <Flex
          align="center"
          color="textMuted"
          css={({ density }) => ({ gap: density.space * 1.5 })}
        >
          {readouts}
        </Flex>
      ) : null}
    </Flex>
  );
}

/** @jsxImportSource @emotion/react */
import { Flex } from '../style/prim.tsx';

/** The dot at the corner of a nav item: its ring is the rail's own color, so it reads as cut out of the button. */
export function AttentionDot({ level }: { level: 'notice' | 'urgent' }) {
  return (
    <Flex
      as="span"
      aria-hidden="true"
      position="absolute"
      width={10}
      height={10}
      radius="full"
      css={({ palette }) => ({
        top: 6,
        right: 6,
        background: level === 'urgent' ? palette.danger : palette.warm,
        boxShadow: `0 0 0 2px ${palette.rail}`,
      })}
    />
  );
}

/** @jsxImportSource @emotion/react */
import { Box, Flex } from '../style/prim.tsx';
import { motion } from 'motion/react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';

const SWATCH_SPRING = { type: 'spring', stiffness: 520, damping: 26 } as const;

export interface SwatchItem {
  key: string;
  label: string;
  selected: boolean;
  onPick: () => void;

  /** Literal CSS background of the dot. */
  color: string;

  /** Optional glyph drawn on the dot (the dot grows slightly to fit it). */
  icon?: IconName;
}

/** The row of circular choices a tile shows in place of its label (light colors, climate modes):
 * springs in left to right, with the selected one ringed. `customLabel` + `onCustom` add a trailing
 * rainbow "custom" swatch after a divider. */
export function SwatchRow({
  items,
  customLabel,
  onCustom,
}: {
  items: SwatchItem[];
  customLabel?: string;
  onCustom?: () => void;
}) {
  return (
    <Flex
      as={motion.div}
      align="center"
      justify="center"
      gap={0.5}
      minWidth={0}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      transition={{ duration: 0.15 }}
    >
      {items.map((item, index) => (
        <Flex
          type="button"
          key={item.key}
          as={motion.button}
          initial={{ scale: 0, opacity: 0 }}
          animate={{ scale: 1, opacity: 1 }}
          transition={{ ...SWATCH_SPRING, delay: index * 0.04 }}
          aria-label={item.label}
          aria-pressed={item.selected}
          title={item.label}
          onClick={item.onPick}
          center
          width={30}
          height={30}
          p={0}
          radius="full"
          cursor="pointer"
          css={{ flex: 'none', background: 'transparent' }}
        >
          <Flex
            as="span"
            center
            width={item.icon ? 24 : 20}
            height={item.icon ? 24 : 20}
            radius="full"
            color="accentText"
            css={({ palette }) => ({
              background: item.color,
              // A ring in the page's own colors, so it reads on either theme.
              boxShadow: item.selected
                ? `0 0 0 2px ${palette.surface}, 0 0 0 4px ${palette.text}`
                : 'none',
            })}
          >
            {item.icon ? <Icon name={item.icon} size={14} /> : null}
          </Flex>
        </Flex>
      ))}
      {onCustom ? (
        <>
          {items.length > 0 ? (
            <Box
              as="span"
              aria-hidden="true"
              width={1}
              height={24}
              mx={1.5}
              background="line"
              css={{ opacity: 0.4 }}
            />
          ) : null}
          <Flex
            as={motion.button}
            initial={{ scale: 0, opacity: 0 }}
            animate={{ scale: 1, opacity: 1 }}
            transition={{ ...SWATCH_SPRING, delay: items.length * 0.04 }}
            type="button"
            aria-label={customLabel ?? 'Custom'}
            title={customLabel ?? 'Custom'}
            onClick={onCustom}
            center
            width={30}
            height={30}
            p={0}
            radius="full"
            cursor="pointer"
            css={{ flex: 'none', background: 'transparent' }}
          >
            <Flex
              as="span"
              center
              width={20}
              height={20}
              radius="full"
              css={{
                background:
                  'conic-gradient(#ff5a4d, #ffd24a, #5fd17a, #4dc9ff, #6a7bff, #c75bff, #ff5a4d)',
              }}
            >
              <Box as="span" width={8} height={8} radius="full" background="surface" />
            </Flex>
          </Flex>
        </>
      ) : null}
    </Flex>
  );
}

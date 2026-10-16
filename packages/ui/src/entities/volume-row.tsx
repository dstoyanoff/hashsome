/** @jsxImportSource @emotion/react */
import { Flex, Grid, Typography } from 'e-prim';
import { motion } from 'motion/react';
import { Icon } from '../icon.tsx';
import { ValueBar } from '../layout/drawer-controls.tsx';
import { PlainButton } from '../layout/plain-button.tsx';
import { IconButton } from '../layout/tile.tsx';
import type { useVolumeControl } from './media-controls.ts';
import { useStill } from '../layout/use-still.ts';

/** How long the button row and the volume take to fade, one out and the other in. */
export const ROW_FADE = 0.12;

/** The volume in the place of a player's button row, as the player card shows it: the mute button, the slider and the
 * level across the first four slots, and a cross in the last, where the volume button was, to give the buttons back.
 * It has the row's own five slots, so it is as wide as the row and the cross does not move. Nothing floats, so the
 * player keeps its height. */
export function VolumeRow({
  volume,
  canMute = true,
  onClose,
}: {
  volume: ReturnType<typeof useVolumeControl>;

  /** Whether the player can be muted; without it the mute button is left out. Default `true`. */
  canMute?: boolean;
  onClose: () => void;
}) {
  const still = useStill();
  return (
    <Grid
      as={motion.div}
      gap={3}
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      transition={{ duration: still ? 0 : ROW_FADE, ease: 'easeOut' }}
      css={({ density }) => ({
        gridTemplateColumns: `repeat(5, ${density.iconCircle}px)`,
        justifyContent: 'center',
        alignItems: 'center',
      })}
    >
      <Flex align="center" gap={3} minWidth={0} css={{ gridColumn: '1 / 5' }}>
        {canMute ? (
          <PlainButton
            aria-label={volume.muted ? 'Unmute' : 'Mute'}
            aria-pressed={volume.muted}
            title={volume.muted ? 'Unmute' : 'Mute'}
            onClick={volume.toggleMute}
            center
            color={volume.muted ? 'accent' : 'textMuted'}
            css={{ flex: 'none' }}
          >
            <Icon name={volume.muted ? 'lu:volume-x' : 'lu:volume-2'} size={18} />
          </PlainButton>
        ) : null}
        <ValueBar
          label="Volume level"
          value={volume.shown}
          min={0}
          max={100}
          keyStep={5}
          height={10}
          onDrag={volume.drag}
          onCommit={volume.commit}
        />
        <Typography
          as="span"
          variant="secondary"
          color="textMuted"
          minWidth={32}
          align="right"
          css={{ flex: 'none', fontVariantNumeric: 'tabular-nums' }}
        >
          {volume.shown}%
        </Typography>
      </Flex>
      <IconButton icon="lu:x" label="Close volume" glyph={18} onClick={onClose} />
    </Grid>
  );
}

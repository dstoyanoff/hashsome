/** @jsxImportSource @emotion/react */
import { sizedAssetUrl, type AssetSize } from '@hashsome/core';
import { Box } from '../style/prim.tsx';

/** A picture (artwork, an avatar) that fills the box it is in, cropped to fit. Decorative: the
 * name of what it shows is always written beside it. A picture the runtime serves is asked for at the `size` given, which
 * its backend maps to a size of its own: `small` for a thumbnail (a list's row, the small player), `medium` for a
 * cover (a card, the player's), `large` for a picture that fills a screen. One from anywhere else is used as it is. */
export function Cover({ src, size }: { src: string; size: AssetSize }) {
  return (
    <Box
      as="img"
      src={sizedAssetUrl(src, size)}
      alt=""
      loading="lazy"
      decoding="async"
      width="100%"
      height="100%"
      css={{ objectFit: 'cover' }}
    />
  );
}

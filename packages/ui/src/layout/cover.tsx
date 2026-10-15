/** @jsxImportSource @emotion/react */
import { sizedAssetUrl } from '@hashsome/core';
import { Box } from 'e-prim';

/** How wide, in pixels, a picture is asked for: a `small` one is for a thumbnail (a list's row, the small player's
 * ring, about 44 px), a `large` one for a cover the whole size of a card or the player (about 200 px). A picture the
 * backend resizes is a fraction of the weight: Music Assistant's is 4 kB at 80 and 30 kB at 256, against 108 at 512. */
const PIXELS = { small: 80, large: 256 } as const;

/** A picture (artwork, an avatar) that fills the box it is in, cropped to fit. Decorative: the
 * name of what it shows is always written beside it. A picture the runtime serves is asked for at the `size` given, not
 * the one it is stored at; one from anywhere else is used as it is. */
export function Cover({ src, size }: { src: string; size: keyof typeof PIXELS }) {
  return (
    <Box
      as="img"
      src={sizedAssetUrl(src, PIXELS[size])}
      alt=""
      width="100%"
      height="100%"
      css={{ objectFit: 'cover' }}
    />
  );
}

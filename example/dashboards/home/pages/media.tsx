import { MediaBrowser, MediaPlayerFull, MediaQueue } from '@hashsome/ui';
import styles from './media.module.css';

// The page is the dashboard's own: a surface that fills the space and scrolls inside it, around the
// player, its queue (beside it) and its library. `MediaPlayerFull` only lays them out; change the
// rest as you like.
export default function Media() {
  return (
    <div className={styles.page}>
      <MediaPlayerFull
        entity="ma:living_room"
        wide
        queue={<MediaQueue entity="ma:living_room" />}
        browser={<MediaBrowser entity="ma:living_room" layout="theater" />}
      />
    </div>
  );
}

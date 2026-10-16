import type { MediaPlayerEntity } from '@hashsome/core';
import { useEffect, useState } from 'react';

/** `m:ss`, or `h:mm:ss` from an hour up. */
export function formatDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = String(total % 60).padStart(2, '0');
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`;
}

/** How long something plays, said plainly: `42 min`, `2 h`, `7 h 33 min`. For a whole queue or album, not a position. */
export function formatLength(seconds: number): string {
  const minutes = Math.round(Math.max(0, seconds) / 60);
  if (minutes < 60) {
    return `${Math.max(1, minutes)} min`;
  }

  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}

/** Where playback is now. The player reports a position as of a moment; while it plays, this
 * moves it forward from there on its own clock, so the bar advances without a message per second.
 * `undefined` until the player reports a position. */
export function useMediaPosition(player: MediaPlayerEntity | undefined): number | undefined {
  const playing = player?.playback === 'playing';
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!playing) {
      return;
    }

    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, [playing]);

  if (player?.position === undefined) {
    return undefined;
  }

  const since = player.positionUpdatedAt ? Date.parse(player.positionUpdatedAt) : Number.NaN;
  const elapsed = playing && Number.isFinite(since) ? Math.max(0, (now - since) / 1000) : 0;

  const position = player.position + elapsed;
  return player.duration === undefined ? position : Math.min(player.duration, position);
}

/** How long after a track changes the player may dip out of playing without the button showing it. */
const NEW_TRACK_MS = 6000;

/** How long such a dip has to last to be believed. */
const DIP_MS = 800;

/** Whether to show the player as playing: `playback === 'playing'`, except that a player which flips to
 * idle and back within a moment as a new track starts (as Music Assistant's do) does not make the button
 * change to Play and back. A pause at any other time shows at once. */
export function useSteadyPlaying(player: MediaPlayerEntity | undefined): boolean {
  const raw = player?.playback === 'playing';
  const track = player?.media?.title;

  // A track change opens a short window, in which a dip out of playing is held up for a moment.
  const [seen, setSeen] = useState(track);
  const [recent, setRecent] = useState(false);
  if (seen !== track) {
    setSeen(track);
    setRecent(true);
  }

  useEffect(() => {
    if (!recent) {
      return;
    }

    const id = setTimeout(() => setRecent(false), NEW_TRACK_MS);
    return () => clearTimeout(id);
    // `track` is not read inside: another change of track restarts the window.
    // oxlint-disable-next-line react/exhaustive-effect-dependencies
  }, [recent, track]);

  const [was, setWas] = useState(raw);
  const [held, setHeld] = useState(false);
  if (raw !== was) {
    setWas(raw);
    setHeld(!raw && recent);
  }

  useEffect(() => {
    if (!held) {
      return;
    }

    const id = setTimeout(() => setHeld(false), DIP_MS);
    return () => clearTimeout(id);
  }, [held]);

  return raw || held;
}

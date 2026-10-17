import type { ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import styles from './room-header.module.css';

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
    <header data-grid-card className={styles.header}>
      {icon ? <Icon name={icon} size={16} /> : null}
      <h2 className={styles.title}>{title}</h2>
      <span className={styles.line} />
      {readouts ? <div className={styles.readouts}>{readouts}</div> : null}
    </header>
  );
}

import type { CSSProperties, ReactNode } from 'react';
import styles from './icon.module.css';

export interface IconProps {
  /** The icon: any SVG element, e.g. `<Lightbulb />` from `lucide-react` or `<IconBath />` from `@tabler/icons-react`. */
  children: ReactNode;

  /** Overrides the density's icon size, in px. */
  size?: number;
}

/** Sizes and draws an icon the way the rest of the app does: the density's icon size, a thin outline, the surrounding text color.
 * It does not matter which icon library the icon comes from. */
export function Icon({ children, size }: IconProps) {
  return (
    <span
      className={styles.icon}
      {...(size === undefined ? {} : { style: { '--hs-icon-size': `${size}px` } as CSSProperties })}
      aria-hidden="true"
    >
      {children}
    </span>
  );
}

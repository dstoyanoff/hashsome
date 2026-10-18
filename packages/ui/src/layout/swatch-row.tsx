import type { CSSProperties, ReactNode } from 'react';
import { Icon } from '../icon.tsx';
import { cx } from '../style/cx.ts';
import styles from './swatch-row.module.css';

export interface SwatchItem {
  key: string;
  label: string;
  selected: boolean;
  onPick: () => void;

  /** Literal CSS background of the dot. */
  color: string;

  /** Optional glyph drawn on the dot (the dot grows slightly to fit it). */
  icon?: ReactNode;
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
    <div className={styles.row}>
      {items.map((item, index) => (
        <button
          type="button"
          key={item.key}
          style={{ '--i': index } as CSSProperties}
          aria-label={item.label}
          aria-pressed={item.selected}
          title={item.label}
          onClick={item.onPick}
          className={styles.swatch}
        >
          <span
            className={cx(
              styles.dot,
              item.icon ? styles.withIcon : false,
              item.selected && styles.selected,
            )}
            style={{ background: item.color }}
          >
            {item.icon ? <Icon size={14}>{item.icon}</Icon> : null}
          </span>
        </button>
      ))}
      {onCustom ? (
        <>
          {items.length > 0 ? <span aria-hidden="true" className={styles.divider} /> : null}
          <button
            style={{ '--i': items.length } as CSSProperties}
            type="button"
            aria-label={customLabel ?? 'Custom'}
            title={customLabel ?? 'Custom'}
            onClick={onCustom}
            className={styles.swatch}
          >
            <span className={cx(styles.dot, styles.custom)}>
              <span className={styles.hub} />
            </span>
          </button>
        </>
      ) : null}
    </div>
  );
}

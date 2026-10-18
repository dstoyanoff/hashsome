/** The class names that are there, joined: `cx(styles.tile, active && styles.active)`. */
export function cx(...names: (string | false | null | undefined)[]): string {
  return names.filter(Boolean).join(' ');
}

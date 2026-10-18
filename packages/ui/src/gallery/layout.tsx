import type { CSSProperties, ElementType, ReactNode } from 'react';
import { kebab, type PaletteName, type RadiusName, type TypographyName } from '../style/names.ts';
import styles from './gallery.module.css';

const unit = (n: number) => `calc(var(--hs-unit) * ${n})`;
const size = (value: number | string) => (typeof value === 'number' ? `${value}px` : value);

interface FlexProps {
  as?: ElementType;
  direction?: 'row' | 'column';
  gap?: number;
  align?: CSSProperties['alignItems'];
  justify?: CSSProperties['justifyContent'];
  center?: boolean;
  grow?: number;
  width?: number | string;
  height?: number | string;
  minHeight?: number;
  maxWidth?: number | string;
  maxHeight?: number;
  overflow?: CSSProperties['overflow'];
  p?: number;
  px?: number;
  mb?: number;
  background?: PaletteName;
  color?: PaletteName;
  radius?: RadiusName;
  border?: boolean;
  position?: CSSProperties['position'];
  wrap?: boolean;
  style?: CSSProperties;
  children?: ReactNode;
}

/** The gallery's own flex box: a few of the props the demos need, written as inline styles (the gallery is a page, not a component). */
export function Flex({
  as: Tag = 'div',
  direction,
  gap,
  align,
  justify,
  center,
  grow,
  width,
  height,
  minHeight,
  maxWidth,
  maxHeight,
  overflow,
  p,
  px,
  mb,
  background,
  color,
  radius,
  border,
  position,
  wrap,
  style,
  children,
}: FlexProps) {
  const css: CSSProperties = {
    display: 'flex',
    ...(direction ? { flexDirection: direction } : {}),
    ...(gap !== undefined ? { gap: unit(gap) } : {}),
    ...(center ? { alignItems: 'center', justifyContent: 'center' } : {}),
    ...(align ? { alignItems: align } : {}),
    ...(justify ? { justifyContent: justify } : {}),
    ...(grow !== undefined ? { flexGrow: grow } : {}),
    ...(width !== undefined ? { width: size(width) } : {}),
    ...(height !== undefined ? { height: size(height) } : {}),
    ...(minHeight !== undefined ? { minHeight: size(minHeight) } : {}),
    ...(maxWidth !== undefined ? { maxWidth: size(maxWidth) } : {}),
    ...(maxHeight !== undefined ? { maxHeight: size(maxHeight) } : {}),
    ...(overflow ? { overflow } : {}),
    ...(p !== undefined ? { padding: unit(p) } : {}),
    ...(px !== undefined ? { paddingInline: unit(px) } : {}),
    ...(mb !== undefined ? { marginBottom: unit(mb) } : {}),
    ...(background ? { background: `var(--hs-${kebab(background)})` } : {}),
    ...(color ? { color: `var(--hs-${kebab(color)})` } : {}),
    ...(radius ? { borderRadius: `var(--hs-radius-${kebab(radius)})` } : {}),
    ...(border ? { border: 'var(--hs-border-width) solid var(--hs-border)' } : {}),
    ...(position ? { position } : {}),
    ...(wrap ? { flexWrap: 'wrap' } : {}),
    ...style,
  };

  return <Tag style={css}>{children}</Tag>;
}

/** Text in one of the theme's type variants. */
export function Typography({
  as: Tag = 'span',
  variant = 'default',
  color,
  uppercase,
  className,
  style,
  children,
}: {
  as?: ElementType;
  variant?: TypographyName;
  color?: PaletteName;
  uppercase?: boolean;
  className?: string | undefined;
  style?: CSSProperties;
  children?: ReactNode;
}) {
  return (
    <Tag
      className={[styles[variant], className].filter(Boolean).join(' ')}
      style={{
        ...(color ? { color: `var(--hs-${kebab(color)})` } : {}),
        ...(uppercase ? { textTransform: 'uppercase' } : {}),
        ...style,
      }}
    >
      {children}
    </Tag>
  );
}

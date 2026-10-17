/** @jsxImportSource @emotion/react */
import { Flex, Typography } from '../style/prim.tsx';
import { useEffect, useId, type ReactNode } from 'react';
import type { IconName } from '../icon-data.ts';
import { Icon } from '../icon.tsx';
import { useDetail } from './detail-provider.tsx';

export function DrawerHeader({
  icon,
  label,
  kind,
}: {
  icon?: IconName | undefined;
  label: string;
  kind?: string | undefined;
}) {
  return (
    <Flex align="center" gap={2.5}>
      {icon ? (
        <Flex
          align="center"
          justify="center"
          background="surfaceRaised"
          color="text"
          radius="full"
          width={34}
          height={34}
          css={{ flex: 'none' }}
        >
          <Icon name={icon} size={16} />
        </Flex>
      ) : null}
      <Flex direction="column">
        <Typography as="span" variant="heading">
          {label}
        </Typography>
        {kind ? (
          <Typography as="span" variant="secondary" color="textMuted">
            {kind}
          </Typography>
        ) : null}
      </Flex>
    </Flex>
  );
}

/** Opens the shared `EntityDrawer` for one owner (a tile, a readout) and keeps it live: `body` is
 * a fresh element every render (its props can change while the drawer is open — including from an
 * action taken inside it), but `open` only fires once, so an effect re-pushes the content for as
 * long as this owner's drawer is the open one. */
export function useDrawer({
  icon,
  label,
  kind,
  body,
}: {
  icon?: IconName | undefined;
  label: string;
  kind?: string | undefined;
  body: ReactNode;
}) {
  const id = useId();
  const { detail, openDetail, updateDetail } = useDetail();
  const isOpen = detail?.id === id;

  useEffect(() => {
    if (isOpen) {
      updateDetail(id, <DrawerHeader icon={icon} label={label} kind={kind} />, body);
    }
  }, [isOpen, id, icon, label, kind, body, updateDetail]);

  const open = () => openDetail(id, <DrawerHeader icon={icon} label={label} kind={kind} />, body);
  const openExpanded = () =>
    openDetail(id, <DrawerHeader icon={icon} label={label} kind={kind} />, body, true);

  // Full size and kept there: for content that is an overlay of its own, with nothing to collapse to.
  const openFull = () =>
    openDetail(id, <DrawerHeader icon={icon} label={label} kind={kind} />, body, true, true);

  return { open, openExpanded, openFull, isOpen };
}

/** `useDrawer` for an owner that must not itself subscribe to the drawer's context. Re-pushing the
 * body on every context change re-renders whoever called the hook; if that is the same component
 * that built `body`, it makes a fresh element each time and loops. Hosting the hook in this child
 * keeps `body`'s identity tied to the parent's own renders (entity updates), not the drawer's. */
export function DrawerTrigger({
  icon,
  label,
  kind,
  body,
  children,
}: {
  icon?: IconName | undefined;
  label: string;
  kind?: string | undefined;
  body: ReactNode;

  /** Gets `open` (the side panel), `openExpanded` (full size, which can be collapsed) and `openFull` (full size, with no collapse button). */
  children: (open: () => void, openExpanded: () => void, openFull: () => void) => ReactNode;
}) {
  const { open, openExpanded, openFull } = useDrawer({ icon, label, kind, body });
  return <>{children(open, openExpanded, openFull)}</>;
}

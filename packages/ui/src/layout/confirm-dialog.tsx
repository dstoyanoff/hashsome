/** @jsxImportSource @emotion/react */
import { Box, Flex, Typography } from '../style/prim.tsx';
import { AnimatePresence, motion } from 'motion/react';
import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';

export interface ConfirmDialogProps {
  /** Whether the dialog is showing. */
  open: boolean;

  /** What is being asked, as a short question: "Mark the septic additive as done?". */
  title: string;

  /** A line under the title. */
  description?: ReactNode;

  /** Label of the button that goes ahead. Default `Confirm`. */
  confirmLabel?: string;

  /** Label of the button that backs out. Default `Cancel`. */
  cancelLabel?: string;

  /** The person went ahead. */
  onConfirm: () => void;

  /** The person backed out: the Cancel button, a tap outside, or Escape. */
  onCancel: () => void;

  /** Something is being done with the answer: both buttons wait. */
  busy?: boolean;

  /** More to ask for before going ahead, e.g. a date. */
  children?: ReactNode;
}

const FOCUSABLE =
  'button:not(:disabled), [href], input:not(:disabled), [tabindex]:not([tabindex="-1"])';

/**
 * A question that has to be answered before something is done, so a stray tap cannot do it: a card in the
 * middle of the page over a dimmed scrim, with a way to back out (Cancel, a tap outside, Escape) and a button
 * to go ahead. It is drawn over the whole page, whatever it is opened from. Focus moves into it while it is
 * open and returns to what had it after, and Tab stays inside.
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  busy = false,
  children,
}: ConfirmDialogProps) {
  const heading = useId();
  const about = useId();
  const card = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }

    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    card.current?.focus();
    return () => before?.focus();
  }, [open]);

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.stopPropagation();
      onCancel();
      return;
    }

    if (event.key === 'Tab' && card.current) {
      const inside = [...card.current.querySelectorAll<HTMLElement>(FOCUSABLE)];
      const first = inside[0];
      const last = inside.at(-1);
      if (
        event.shiftKey &&
        (document.activeElement === first || document.activeElement === card.current)
      ) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
  };

  return createPortal(
    <AnimatePresence>
      {open ? (
        <Flex
          as={motion.div}
          key="scrim"
          align="center"
          justify="center"
          position="fixed"
          zIndex="modal"
          p={4}
          onClick={onCancel}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.16 }}
          css={({ palette }) => ({
            inset: 0,
            // The page's own color, so the dimming follows the theme.
            background: `color-mix(in srgb, ${palette.bg} 72%, transparent)`,
          })}
        >
          <Flex
            as={motion.div}
            ref={card}
            role="dialog"
            aria-modal="true"
            aria-labelledby={heading}
            {...(description ? { 'aria-describedby': about } : {})}
            tabIndex={-1}
            direction="column"
            gap={4}
            background="surfaceRaised"
            radius="card"
            shadow="drawer"
            p={5}
            onClick={(event: { stopPropagation: () => void }) => event.stopPropagation()}
            onKeyDown={onKeyDown}
            initial={{ opacity: 0, scale: 0.96, y: 8 }}
            animate={{ opacity: 1, scale: 1, y: 0 }}
            exit={{ opacity: 0, scale: 0.98 }}
            transition={{ duration: 0.18, ease: 'easeOut' }}
            // Scrolls when what is in it does not fit the screen, rather than running off it.
            css={{
              width: 'min(420px, 100%)',
              maxHeight: '100%',
              overflowY: 'auto',
              outline: 'none',
            }}
          >
            <Flex direction="column" gap={1.5}>
              <Typography as="h2" variant="heading" id={heading}>
                {title}
              </Typography>
              {description ? (
                <Typography as="p" variant="body" color="textMuted" id={about}>
                  {description}
                </Typography>
              ) : null}
            </Flex>
            {children ? <Box>{children}</Box> : null}
            <Flex justify="flex-end" gap={2}>
              <DialogButton label={cancelLabel} disabled={busy} onClick={onCancel} />
              <DialogButton label={confirmLabel} primary disabled={busy} onClick={onConfirm} />
            </Flex>
          </Flex>
        </Flex>
      ) : null}
    </AnimatePresence>,
    document.body,
  );
}

function DialogButton({
  label,
  primary = false,
  disabled,
  onClick,
}: {
  label: string;
  primary?: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  return (
    <Flex
      as="button"
      type="button"
      onClick={onClick}
      disabled={disabled}
      align="center"
      justify="center"
      radius="full"
      cursor={disabled ? 'default' : 'pointer'}
      height={40}
      px={5}
      background={primary ? 'accent' : 'surface'}
      color={primary ? 'accentText' : 'text'}
      css={{ opacity: disabled ? 0.5 : 1 }}
    >
      <Typography as="span" variant="label">
        {label}
      </Typography>
    </Flex>
  );
}

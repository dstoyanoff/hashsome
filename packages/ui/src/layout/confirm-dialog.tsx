import { usePresence } from '../motion/presence.ts';
import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { cx } from '../style/cx.ts';
import styles from './confirm-dialog.module.css';

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
  // Drawn a moment after it is closed, in full motion, so it can leave (see `usePresence`).
  const { mounted, leaving } = usePresence(open, 180);

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
    <>
      {mounted ? (
        <div className={styles.scrim} data-leaving={leaving} onClick={onCancel}>
          <div
            ref={card}
            className={styles.card}
            role="dialog"
            aria-modal="true"
            aria-labelledby={heading}
            {...(description ? { 'aria-describedby': about } : {})}
            tabIndex={-1}
            onClick={(event) => event.stopPropagation()}
            onKeyDown={onKeyDown}
          >
            <div className={styles.titles}>
              <h2 className={styles.title} id={heading}>
                {title}
              </h2>
              {description ? (
                <p className={styles.description} id={about}>
                  {description}
                </p>
              ) : null}
            </div>
            {children ? <div>{children}</div> : null}
            <div className={styles.actions}>
              <DialogButton label={cancelLabel} disabled={busy} onClick={onCancel} />
              <DialogButton label={confirmLabel} primary disabled={busy} onClick={onConfirm} />
            </div>
          </div>
        </div>
      ) : null}
    </>,
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
    <button
      type="button"
      className={cx(styles.button, primary && styles.primary)}
      onClick={onClick}
      disabled={disabled}
    >
      {label}
    </button>
  );
}

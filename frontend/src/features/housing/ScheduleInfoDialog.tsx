import { useId, useRef, type ReactNode } from 'react';
import styles from './IndividualAnnouncement.module.css';

export function ScheduleInfoDialog({
    label,
    title,
    buttonLabel,
    children,
}: {
    label: string;
    title: string;
    buttonLabel?: string;
    children: ReactNode;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const titleId = useId();
    return (
        <>
            <button
                type="button"
                className={styles.trigger}
                aria-haspopup="dialog"
                aria-label={buttonLabel}
                onClick={() => dialog.current?.showModal()}
            >
                {label}
            </button>
            <dialog
                ref={dialog}
                className={styles.dialog}
                aria-labelledby={titleId}
            >
                <header>
                    <h2 id={titleId}>{title}</h2>
                    <button
                        type="button"
                        aria-label="닫기"
                        className={styles.close}
                        onClick={() => dialog.current?.close()}
                    >
                        ×
                    </button>
                </header>
                {children}
            </dialog>
        </>
    );
}

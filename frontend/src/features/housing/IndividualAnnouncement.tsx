import { useId, useRef } from 'react';
import type { Schedule } from './types';
import { EvidenceLink } from './EvidenceLink';
import styles from './IndividualAnnouncement.module.css';

export function IndividualAnnouncement({
    event,
}: {
    event: Omit<Schedule, 'recorded'>;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const title = useId();
    return (
        <>
            <button
                type="button"
                className={styles.trigger}
                aria-haspopup="dialog"
                aria-label="결과 발표 개별 안내 설명"
                onClick={() => dialog.current?.showModal()}
            >
                개별 안내
            </button>
            <dialog
                ref={dialog}
                className={styles.dialog}
                aria-labelledby={title}
            >
                <header>
                    <h2 id={title}>결과 발표 · 개별 안내</h2>
                    <button
                        type="button"
                        aria-label="닫기"
                        className={styles.close}
                        onClick={() => dialog.current?.close()}
                    >
                        ×
                    </button>
                </header>
                <p className={styles.description}>{event.timingDescription}</p>
                <EvidenceLink evidence={event.evidence} checkedAtOnNewLine />
            </dialog>
        </>
    );
}

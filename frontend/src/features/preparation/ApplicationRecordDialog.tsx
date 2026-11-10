import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import type { Preparation } from '../housing/types';
import { api } from '../housing/api';
import { useSaved } from './usePreparation';
import { applicationRecordSchedule } from '../housing/schedules';
import styles from './ApplicationRecordDialog.module.css';

export function ApplicationRecordDialog({
    preparation,
    requested = false,
    onDismiss,
}: {
    preparation: Preparation;
    requested?: boolean;
    onDismiss?: () => void;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const wasRequested = useRef(false);
    const [message, setMessage] = useState('');
    const saved = useSaved();
    const target = applicationRecordSchedule(preparation.schedules);
    const canRecord = preparation.guideState === 'AVAILABLE' && !!target;
    const mutation = useMutation({
        mutationFn: (recorded: boolean) => {
            if (!canRecord || !target)
                throw new Error('신청 기록을 저장할 수 없습니다.');
            return api.schedule(
                preparation.id,
                target.id,
                preparation.revision,
                recorded,
            );
        },
        onSuccess: (next, recorded) => {
            saved(next);
            setMessage(
                recorded
                    ? '신청 완료로 기록했어요.'
                    : '아직 신청하지 않은 상태로 기록했어요.',
            );
            dialog.current?.close();
        },
    });
    const { reset } = mutation;
    const openDialog = useCallback(() => {
        reset();
        setMessage('');
        dialog.current?.showModal();
    }, [reset]);
    useEffect(() => {
        if (requested && !dialog.current?.open) openDialog();
        else if (!requested && wasRequested.current && dialog.current?.open)
            dialog.current.close();
        wasRequested.current = requested;
    }, [requested, openDialog]);
    return (
        <>
            <button type="button" aria-haspopup="dialog" onClick={openDialog}>
                신청 여부 기록
            </button>
            <dialog
                ref={dialog}
                className={styles.dialog}
                aria-label="신청을 완료하셨나요?"
                onClose={onDismiss}
                onCancel={(event) => {
                    if (mutation.isPending) event.preventDefault();
                }}
            >
                <header>
                    <h2>신청을 완료하셨나요?</h2>
                    <button
                        type="button"
                        className={styles.close}
                        aria-label="닫기"
                        disabled={mutation.isPending}
                        onClick={() => dialog.current?.close()}
                    >
                        ×
                    </button>
                </header>
                <p className={styles.notice}>{preparation.title}</p>
                <p className={styles.description}>
                    공식 사이트에서 진행한 신청 여부를 기록해요.
                </p>
                {target?.recorded && (
                    <p className={styles.current}>
                        현재 신청 완료로 기록되어 있어요.
                    </p>
                )}
                {!canRecord && (
                    <p role="status">
                        이 공고는 현재 신청 기록을 저장할 수 없어요.
                    </p>
                )}
                {mutation.error && (
                    <p className={styles.error} role="alert">
                        {mutation.error.message}
                    </p>
                )}
                <div className={styles.actions}>
                    <button
                        type="button"
                        disabled={!canRecord || mutation.isPending}
                        onClick={() => mutation.mutate(false)}
                    >
                        아직이에요
                    </button>
                    <button
                        type="button"
                        className={styles.primary}
                        disabled={!canRecord || mutation.isPending}
                        onClick={() => mutation.mutate(true)}
                    >
                        완료했어요
                    </button>
                </div>
                {mutation.isPending && (
                    <p role="status" className={styles.saving}>
                        저장 중…
                    </p>
                )}
            </dialog>
            {message && (
                <p role="status" className={styles.feedback}>
                    {message}
                </p>
            )}
        </>
    );
}

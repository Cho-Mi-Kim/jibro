import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { useSession } from '../auth/useSession';
import { demoMode } from '../../shared/api/transport';
import { preparationDeletionApi } from './preparationDeletionApi';
import { forgetPreparation } from './useActivePreparation';
import { Icon } from '../../shared/ui/Icon';
import styles from './PreparationDeleteDialog.module.css';

export function PreparationDeleteDialog({
    id,
    afterDeleteTo,
}: {
    id: string;
    afterDeleteTo?: string;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const [open, setOpen] = useState(false);
    const [confirmation, setConfirmation] = useState('');
    const [password, setPassword] = useState('');
    const [code, setCode] = useState('');
    const [byMail, setByMail] = useState(false);
    const [message, setMessage] = useState('');
    const { user } = useSession();
    const client = useQueryClient();
    const navigate = useNavigate();
    const details = useQuery({
        queryKey: ['preparation-deletion', user?.id, id],
        queryFn: () => preparationDeletionApi.details(id),
        enabled: open && !demoMode,
        retry: false,
    });
    const proof = useMutation({
        mutationFn: () =>
            preparationDeletionApi.proof(id, details.data!.revision),
        onSuccess: () =>
            setMessage(
                details.data?.mailMode === 'local'
                    ? '로컬 메일 보관함에 삭제 확인 코드를 준비했습니다.'
                    : '가입한 이메일로 삭제 확인 코드를 보냈습니다.',
            ),
    });
    const deletion = useMutation({
        mutationFn: () =>
            preparationDeletionApi.delete(id, {
                revision: details.data!.revision,
                confirmation,
                ...(details.data?.trial
                    ? {}
                    : usesMail
                      ? { code }
                      : { password }),
            }),
        onSuccess: async () => {
            if (user) forgetPreparation(user.id, id);
            // Cancel requests that could put the deleted snapshot back into a mounted screen.
            const removedKeys = [
                'preparation',
                'preparation-history',
                'preparation-history-detail',
                'preparation-deletion',
            ];
            await Promise.all(
                removedKeys.map((key) =>
                    client.cancelQueries({ queryKey: [key, user?.id, id] }),
                ),
            );
            removedKeys.forEach((key) =>
                client.removeQueries({ queryKey: [key, user?.id, id] }),
            );
            ['preparations', 'archive', 'references'].forEach((key) => {
                client.setQueriesData(
                    { queryKey: [key, user?.id] },
                    (previous: unknown) =>
                        Array.isArray(previous)
                            ? previous.filter((item) => item.id !== id)
                            : previous,
                );
            });
            dialog.current?.close();
            setOpen(false);
            if (afterDeleteTo) navigate(afterDeleteTo, { replace: true });
            await Promise.all(
                ['preparations', 'archive', 'references', 'alerts'].map((key) =>
                    client.invalidateQueries({ queryKey: [key, user?.id] }),
                ),
            );
        },
    });
    const usesMail = !details.data?.passwordLogin || byMail;
    const pending = deletion.isPending || proof.isPending;
    const canDelete =
        details.isSuccess &&
        !details.isFetching &&
        confirmation === '준비 삭제' &&
        (details.data.trial ||
            (usesMail ? code.trim().length > 0 : password.length > 0));
    function close() {
        setOpen(false);
        setConfirmation('');
        setPassword('');
        setCode('');
        setByMail(false);
        setMessage('');
        proof.reset();
        deletion.reset();
    }
    return (
        <>
            <button
                type="button"
                className={styles.trigger}
                disabled={demoMode}
                aria-haspopup="dialog"
                onClick={() => {
                    setOpen(true);
                    dialog.current?.showModal();
                }}
            >
                준비 기록 삭제
            </button>
            {demoMode && (
                <p className={styles.description}>
                    준비 기록 삭제는 서버에 연결된 화면에서 이용할 수 있어요.
                </p>
            )}
            <dialog
                ref={dialog}
                className={styles.dialog}
                aria-labelledby={`delete-preparation-${id}`}
                aria-describedby={
                    details.data ? `delete-warning-${id}` : undefined
                }
                onClose={close}
                onCancel={(event) => {
                    if (pending) event.preventDefault();
                }}
            >
                <header>
                    <h2 id={`delete-preparation-${id}`}>
                        준비 기록을 삭제할까요?
                    </h2>
                    <button
                        type="button"
                        className={styles.close}
                        aria-label="닫기"
                        disabled={pending}
                        onClick={() => dialog.current?.close()}
                    >
                        ×
                    </button>
                </header>
                {details.isPending && (
                    <p className={styles.description} role="status">
                        삭제할 기록을 확인하고 있어요.
                    </p>
                )}
                {details.error && (
                    <p className={styles.error} role="alert">
                        {details.error.message}
                    </p>
                )}
                {details.data && (
                    <form
                        onSubmit={(event) => {
                            event.preventDefault();
                            if (canDelete && !pending) deletion.mutate();
                        }}
                    >
                        <div className={styles.record}>
                            <Icon name="file" />
                            <div>
                                <p className={styles.recordLabel}>
                                    삭제할 준비
                                </p>
                                <p className={styles.title}>
                                    {details.data.title}
                                </p>
                            </div>
                        </div>
                        <div className={styles.warning}>
                            <p
                                id={`delete-warning-${id}`}
                                className={styles.warningTitle}
                            >
                                <Icon name="warning" />
                                삭제한 기록은 복구할 수 없어요.
                            </p>
                            <p className={styles.description}>
                                이 준비의 답변·서류 상태·메모·신청 기록과 이전
                                준비 기록이 함께 삭제돼요.
                            </p>
                            <p className={styles.description}>
                                공고와 다른 준비 기록은 유지돼요.
                            </p>
                        </div>
                        {!details.data.trial && (
                            <div className={styles.authentication}>
                                {details.data.passwordLogin && (
                                    <label className={styles.method}>
                                        <input
                                            type="checkbox"
                                            checked={byMail}
                                            disabled={pending}
                                            onChange={(event) => {
                                                setByMail(event.target.checked);
                                                setPassword('');
                                                setCode('');
                                            }}
                                        />
                                        이메일 확인 코드로 인증
                                    </label>
                                )}
                                {usesMail ? (
                                    <div className={styles.field}>
                                        <label>
                                            삭제 확인 코드
                                            <input
                                                autoComplete="one-time-code"
                                                value={code}
                                                maxLength={100}
                                                disabled={pending}
                                                onChange={(event) =>
                                                    setCode(event.target.value)
                                                }
                                            />
                                        </label>
                                        <button
                                            type="button"
                                            disabled={
                                                pending ||
                                                details.data.mailMode ===
                                                    'unavailable'
                                            }
                                            onClick={() => proof.mutate()}
                                        >
                                            확인 코드 받기
                                        </button>
                                        {details.data.mailMode ===
                                            'unavailable' && (
                                            <p
                                                className={styles.feedback}
                                                role="status"
                                            >
                                                메일 발송 설정이 필요합니다.
                                                {details.data.passwordLogin
                                                    ? ' 현재 비밀번호로 확인해 주세요.'
                                                    : ''}
                                            </p>
                                        )}
                                    </div>
                                ) : (
                                    <label className={styles.field}>
                                        현재 비밀번호
                                        <input
                                            type="password"
                                            autoComplete="current-password"
                                            value={password}
                                            maxLength={72}
                                            disabled={pending}
                                            onChange={(event) =>
                                                setPassword(event.target.value)
                                            }
                                        />
                                    </label>
                                )}
                            </div>
                        )}
                        <label className={styles.field}>
                            확인을 위해 ‘준비 삭제’를 입력해 주세요.
                            <input
                                autoComplete="off"
                                placeholder="준비 삭제"
                                value={confirmation}
                                maxLength={30}
                                disabled={pending}
                                onChange={(event) =>
                                    setConfirmation(event.target.value)
                                }
                            />
                        </label>
                        {message && (
                            <p className={styles.feedback} role="status">
                                {message}
                            </p>
                        )}
                        {(proof.error || deletion.error) && (
                            <p className={styles.error} role="alert">
                                {(proof.error ?? deletion.error)?.message}{' '}
                                기록이 변경되었다면 창을 닫고 다시 확인해
                                주세요.
                            </p>
                        )}
                        <footer>
                            <button
                                type="button"
                                disabled={pending}
                                onClick={() => dialog.current?.close()}
                            >
                                취소
                            </button>
                            <button
                                type="submit"
                                className={styles.danger}
                                disabled={!canDelete || pending}
                            >
                                {deletion.isPending ? '삭제 중…' : '영구 삭제'}
                            </button>
                        </footer>
                    </form>
                )}
            </dialog>
        </>
    );
}

import { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { memberApi } from '../features/member/memberApi';
import { useSession } from '../app/useSession';
import { clearToken, demoMode } from '../shared/api/transport';
import { Button, Heading } from '../shared/ui/ui';
import { mailHint } from '../features/member/mailHint';
import s from '../features/member/Controls.module.css';
export function RecoveryPage() {
    const [email, setEmail] = useState('');
    const mode = useQuery({
        queryKey: ['mail-mode'],
        queryFn: memberApi.mailMode,
        enabled: !demoMode,
    });
    const request = useMutation({
        mutationFn: () => memberApi.requestReset(email),
    });
    return (
        <>
            <Heading title="비밀번호 재설정" backTo="/login" />
            <form
                className={s.section}
                onSubmit={(e) => {
                    e.preventDefault();
                    request.mutate();
                }}
            >
                <label className={s.field}>
                    가입한 이메일
                    <input
                        type="email"
                        required
                        autoComplete="email"
                        value={email}
                        onChange={(e) => setEmail(e.target.value)}
                    />
                </label>
                <p>
                    {demoMode
                        ? '화면 시연에서는 이메일 발송을 제공하지 않습니다.'
                        : mailHint(mode.data?.mode)}
                </p>
                <Button
                    disabled={
                        request.isPending ||
                        demoMode ||
                        !mode.data ||
                        mode.data.mode === 'unavailable'
                    }
                >
                    재설정 링크 요청
                </Button>
                {request.isSuccess && (
                    <p role="status">
                        해당 이메일로 등록한 비밀번호 계정이 있으면 재설정
                        링크가 발급됩니다.
                    </p>
                )}
                {(request.error || mode.error) && (
                    <p role="alert">{(request.error ?? mode.error)?.message}</p>
                )}
            </form>
        </>
    );
}
export function ConfirmAccountPage() {
    const { hash, state, key } = useLocation();
    return (
        <ConfirmForm
            key={key + hash}
            hash={hash}
            complete={hash ? '' : String(state?.completed ?? '')}
        />
    );
}
function ConfirmForm({ hash, complete }: { hash: string; complete: string }) {
    const navigate = useNavigate();
    const params = new URLSearchParams(hash.slice(1));
    const kind =
        complete ||
        (params.has('reset')
            ? 'reset'
            : params.has('verify')
              ? 'verify'
              : 'delete');
    const token = params.get(kind);
    const { user } = useSession(),
        client = useQueryClient();
    const [password, setPassword] = useState(''),
        [confirm, setConfirm] = useState(''),
        [error, setError] = useState('');
    const save = useMutation({
        mutationFn: async () => {
            if (!token) throw Error('링크가 올바르지 않습니다.');
            if (kind === 'reset') await memberApi.reset(token, password);
            else if (kind === 'verify') await memberApi.verify(token);
            else await memberApi.close(token, confirm);
        },
        onSuccess: () => {
            navigate('/account/confirm', {
                replace: true,
                state: { completed: kind },
            });
            if (kind !== 'verify') {
                clearToken();
                client.clear();
            } else
                void client.invalidateQueries({ queryKey: ['member-profile'] });
        },
    });
    const title =
        kind === 'reset'
            ? '새 비밀번호 설정'
            : kind === 'verify'
              ? '이메일 확인'
              : '회원 탈퇴 확인';
    return (
        <>
            <Heading title={title} />
            <section className={s.section}>
                {!complete && (!token || demoMode) ? (
                    <p>유효한 실제 계정의 메일 링크가 필요합니다.</p>
                ) : complete ? (
                    <>
                        <p role="status">{title}을 완료했습니다.</p>
                        <Link to={kind === 'verify' ? '/settings' : '/login'}>
                            {kind === 'verify' ? '계정 설정으로' : '로그인하기'}
                        </Link>
                    </>
                ) : kind === 'delete' && !user ? (
                    <p>
                        이 링크를 발급받은 계정으로 같은 브라우저에서 로그인한
                        뒤 다시 여세요.
                    </p>
                ) : (
                    <form
                        onSubmit={(e) => {
                            e.preventDefault();
                            if (kind === 'reset' && password !== confirm) {
                                setError('비밀번호 확인이 일치하지 않습니다.');
                                return;
                            }
                            setError('');
                            save.mutate();
                        }}
                    >
                        {kind === 'reset' && (
                            <>
                                <label className={s.field}>
                                    새 비밀번호
                                    <input
                                        type="password"
                                        required
                                        minLength={10}
                                        autoComplete="new-password"
                                        value={password}
                                        onChange={(e) =>
                                            setPassword(e.target.value)
                                        }
                                    />
                                </label>
                                <label className={s.field}>
                                    비밀번호 확인
                                    <input
                                        type="password"
                                        required
                                        autoComplete="new-password"
                                        value={confirm}
                                        onChange={(e) =>
                                            setConfirm(e.target.value)
                                        }
                                    />
                                </label>
                            </>
                        )}
                        {kind === 'delete' && (
                            <label className={s.field}>
                                기록 삭제를 확인하려면 ‘회원 탈퇴’를 입력하세요.
                                <input
                                    required
                                    value={confirm}
                                    onChange={(e) => setConfirm(e.target.value)}
                                />
                            </label>
                        )}
                        <Button
                            disabled={
                                save.isPending ||
                                (kind === 'delete' && confirm !== '회원 탈퇴')
                            }
                        >
                            {title}
                        </Button>
                        {(error || save.error) && (
                            <p role="alert">{error || save.error?.message}</p>
                        )}
                    </form>
                )}
            </section>
        </>
    );
}

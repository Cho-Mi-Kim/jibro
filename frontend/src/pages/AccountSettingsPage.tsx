import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { clearToken, demoMode } from '../shared/api/transport';
import { memberApi, type Profile } from '../features/member/memberApi';
import { Button, Heading, State } from '../shared/ui/ui';
import s from '../features/member/Controls.module.css';
import { mailHint } from '../features/member/mailHint';
function ProfileEditor({
    profile,
    onSave,
}: {
    profile: Profile;
    onSave: (p: Profile) => void;
}) {
    const [name, setName] = useState(profile.name),
        [region, setRegion] = useState(profile.region);
    const save = useMutation({
        mutationFn: () => memberApi.edit(name, region),
        onSuccess: (saved) => {
            setName(saved.name);
            setRegion(saved.region);
            onSave(saved);
        },
    });
    return (
        <form
            className={s.section}
            onSubmit={(e) => {
                e.preventDefault();
                save.mutate();
            }}
        >
            <h2>내 정보 관리</h2>
            <label className={s.field}>
                닉네임
                <input
                    value={name}
                    required
                    minLength={2}
                    maxLength={30}
                    onChange={(e) => setName(e.target.value)}
                />
            </label>
            <label className={s.field}>
                관심 지역 (선택)
                <input
                    value={region}
                    maxLength={50}
                    onChange={(e) => setRegion(e.target.value)}
                />
            </label>
            <Button disabled={save.isPending}>정보 저장</Button>
            {save.error && <p role="alert">{save.error.message}</p>}
            {save.isSuccess && <p role="status">저장했습니다.</p>}
        </form>
    );
}
export function AccountSettingsPage() {
    const { user } = useSession(),
        client = useQueryClient(),
        navigate = useNavigate();
    const key = ['member-profile', user?.id],
        q = useQuery({ queryKey: key, queryFn: memberApi.profile });
    const [problem, setProblem] = useState(''),
        [showClosure, setShowClosure] = useState(false);
    const updatePw = useMutation({
        mutationFn: ({
            current,
            password,
        }: {
            current: string;
            password: string;
        }) => memberApi.password(current, password),
        onSuccess: () => {
            clearToken();
            client.clear();
            navigate('/login?returnTo=%2Fsettings', { replace: true });
        },
    });
    const mail = useMutation({
        mutationFn: (purpose: 'verify' | 'delete') =>
            purpose === 'verify'
                ? memberApi.sendVerify()
                : memberApi.sendClosure(),
    });
    function change(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        const form = new FormData(e.currentTarget),
            password = String(form.get('new'));
        if (password !== form.get('confirm')) {
            setProblem('새 비밀번호 확인이 일치하지 않습니다.');
            return;
        }
        setProblem('');
        updatePw.mutate({ current: String(form.get('current')), password });
    }
    return (
        <>
            <Heading title="계정 설정" backTo="/my" />
            <State
                loading={q.isPending}
                error={q.error}
                retry={() => void q.refetch()}
            />
            {q.data && (
                <>
                    <ProfileEditor
                        key={user?.id}
                        profile={q.data}
                        onSave={(p) => {
                            client.setQueryData(key, p);
                            void client.invalidateQueries({
                                queryKey: ['session'],
                            });
                        }}
                    />
                    {!user?.trial && (
                        <section className={s.section}>
                            <h2>이메일</h2>
                            <p>
                                {q.data.email} ·{' '}
                                {q.data.verified ? '확인됨' : '미확인'}
                            </p>
                            {!demoMode && !q.data.verified && (
                                <Button
                                    disabled={
                                        mail.isPending ||
                                        q.data.mailMode === 'unavailable'
                                    }
                                    onClick={() => mail.mutate('verify')}
                                >
                                    이메일 확인 링크 요청
                                </Button>
                            )}
                            <p>
                                {demoMode
                                    ? '시연 계정은 메일을 보내지 않습니다.'
                                    : mailHint(q.data.mailMode)}
                            </p>
                        </section>
                    )}
                    {q.data.passwordLogin && !demoMode && (
                        <form className={s.section} onSubmit={change}>
                            <h2>비밀번호 변경</h2>
                            <label className={s.field}>
                                현재 비밀번호
                                <input
                                    type="password"
                                    name="current"
                                    autoComplete="current-password"
                                    required
                                />
                            </label>
                            <label className={s.field}>
                                새 비밀번호
                                <input
                                    type="password"
                                    name="new"
                                    autoComplete="new-password"
                                    minLength={10}
                                    required
                                />
                            </label>
                            <label className={s.field}>
                                새 비밀번호 확인
                                <input
                                    name="confirm"
                                    type="password"
                                    autoComplete="new-password"
                                    required
                                />
                            </label>
                            <p>변경 후 모든 세션에서 다시 로그인해야 합니다.</p>
                            <Button disabled={updatePw.isPending}>
                                비밀번호 변경
                            </Button>
                            {(problem || updatePw.error) && (
                                <p role="alert">
                                    {problem || updatePw.error?.message}
                                </p>
                            )}
                        </form>
                    )}
                    {!demoMode && !user?.trial && (
                        <section className={s.section}>
                            <button
                                className={s.link}
                                onClick={() => setShowClosure(!showClosure)}
                            >
                                회원 탈퇴 안내
                            </button>
                            {showClosure && (
                                <>
                                    <p>
                                        탈퇴하면 준비 기록과 계정 정보가
                                        삭제됩니다. 이메일로 받은 링크에서 다시
                                        확인해야 탈퇴가 완료됩니다.
                                    </p>
                                    <Button
                                        className="secondary"
                                        disabled={
                                            mail.isPending ||
                                            q.data.mailMode === 'unavailable'
                                        }
                                        onClick={() => mail.mutate('delete')}
                                    >
                                        탈퇴 확인 링크 요청
                                    </Button>
                                </>
                            )}
                        </section>
                    )}
                    {mail.isSuccess && (
                        <p role="status">
                            링크를 요청했습니다. {mailHint(q.data.mailMode)}
                        </p>
                    )}
                    {mail.error && <p role="alert">{mail.error.message}</p>}
                </>
            )}
        </>
    );
}

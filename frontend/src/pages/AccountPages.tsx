import { cx } from '../shared/styles/classes';
import { safeReturn } from '../shared/api/returnPath';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import { demoMode } from '../shared/api/transport';
import { Button, Card, Heading, Soon, State } from '../shared/ui/ui';
export function LoginPage({ signup = false }: { signup?: boolean }) {
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const client = useQueryClient();
    const [error, setError] = useState('');
    const returnTo = safeReturn(params.get('returnTo'));
    const mutation = useMutation({
        mutationFn: async ({
            name,
            email,
            password,
        }: {
            name: string;
            email: string;
            password: string;
        }) => {
            if (signup) await api.signup(name, email, password);
            return api.login(email, password);
        },
        onSuccess: async (user) => {
            await client.cancelQueries();
            client.setQueryData(['session'], user);
            client.removeQueries({
                predicate: (q) => q.queryKey[0] !== 'session',
            });
            navigate(returnTo, { replace: true });
        },
    });
    function submit(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        const form = new FormData(e.currentTarget);
        const name = String(form.get('name') ?? '');
        const email = String(form.get('email') ?? '');
        const password = String(form.get('password') ?? '');
        setError('');
        if (signup && (name.trim().length < 2 || password.length < 10)) {
            setError('이름은 2자 이상, 비밀번호는 10자 이상 입력해 주세요.');
            return;
        }
        mutation.mutate({ name, email, password });
    }
    function social(provider: string) {
        sessionStorage.setItem('jibro.returnTo', returnTo);
        window.location.assign('/oauth2/authorization/' + provider);
    }
    return (
        <>
            <Heading title={signup ? '회원가입' : '로그인'}>
                내 준비를 저장하고 다음에 이어가세요.
            </Heading>
            <Card>
                <form onSubmit={submit}>
                    {signup && (
                        <label>
                            이름
                            <input
                                name="name"
                                autoComplete="name"
                                required
                                minLength={2}
                                maxLength={30}
                            />
                        </label>
                    )}
                    <label>
                        이메일
                        <input
                            name="email"
                            type="email"
                            autoComplete="email"
                            required
                        />
                    </label>
                    <label>
                        비밀번호
                        <input
                            name="password"
                            type="password"
                            autoComplete={
                                signup ? 'new-password' : 'current-password'
                            }
                            required
                            minLength={signup ? 10 : 1}
                            maxLength={72}
                        />
                    </label>
                    {(error || mutation.error) && (
                        <p role="alert">{error || mutation.error?.message}</p>
                    )}
                    <Button disabled={mutation.isPending}>
                        {mutation.isPending
                            ? '처리 중…'
                            : signup
                              ? '가입 후 로그인'
                              : '로그인'}
                    </Button>
                </form>
            </Card>
            {demoMode ? (
                <Card>
                    <p>
                        개발용 시연입니다. 실제 이메일·비밀번호를 입력하지
                        마세요.
                    </p>
                    <Button
                        onClick={() =>
                            mutation.mutate({
                                name: '시연',
                                email: 'demo@example.invalid',
                                password: 'demo-only-password',
                            })
                        }
                    >
                        시연 사용자로 보기
                    </Button>
                    <Button
                        className={cx('secondary')}
                        onClick={() =>
                            mutation.mutate({
                                name: '시연',
                                email: 'admin@demo.local',
                                password: 'demo-only-password',
                            })
                        }
                    >
                        시연 관리자로 보기
                    </Button>
                </Card>
            ) : (
                <Card>
                    <p>다른 계정으로 계속하기</p>
                    <Button
                        className={cx('secondary')}
                        onClick={() => social('google')}
                    >
                        Google로 로그인
                    </Button>
                    <Button
                        className={cx('secondary')}
                        onClick={() => social('kakao')}
                    >
                        카카오로 로그인
                    </Button>
                </Card>
            )}
            <Link
                to={
                    (signup ? '/login' : '/signup') +
                    '?returnTo=' +
                    encodeURIComponent(returnTo)
                }
            >
                {signup ? '이미 계정이 있어요' : '이메일로 회원가입'}
            </Link>
            <Soon label="비밀번호 재설정" />
        </>
    );
}
export function CallbackPage() {
    const { user, loading, error } = useSession();
    const navigate = useNavigate();
    useEffect(() => {
        if (user) {
            const target = safeReturn(sessionStorage.getItem('jibro.returnTo'));
            sessionStorage.removeItem('jibro.returnTo');
            navigate(target, { replace: true });
        }
    }, [user, navigate]);
    return (
        <>
            <Heading title="로그인 확인" />
            <State loading={loading} error={error} />
            {!loading && !user && (
                <Card>
                    <p>로그인을 완료하지 못했습니다. 다시 시도해 주세요.</p>
                    <Link to="/login">로그인으로 돌아가기</Link>
                </Card>
            )}
        </>
    );
}
export function MyPage() {
    const { user } = useSession();
    const client = useQueryClient();
    const navigate = useNavigate();
    const logout = useMutation({
        mutationFn: api.logout,
        onSuccess: async () => {
            await client.cancelQueries();
            client.setQueryData(['session'], null);
            client.removeQueries({
                predicate: (q) => q.queryKey[0] !== 'session',
            });
            navigate('/');
        },
    });
    return (
        <>
            <Heading title="마이" />
            <Card>
                <div className={cx('avatar')}>
                    {user?.name.slice(0, 1) ?? 'J'}
                </div>
                <h2>
                    {user ? user.name + '님' : '로그인하고 준비를 이어가세요'}
                </h2>
                {user ? (
                    <>
                        <p>{user.email}</p>
                        <Link to="/preparations">내 준비 기록 보기 →</Link>
                    </>
                ) : (
                    <Link className={cx('link-button')} to="/login">
                        로그인
                    </Link>
                )}
            </Card>
            <Card>
                <h2>서비스 이용 안내</h2>
                <p>
                    집으로는 신청 준비를 돕는 서비스입니다. 공식 신청과 최종
                    심사는 각 기관에서 진행합니다.
                </p>
                <p>준비도는 기록한 작업의 진행 상태입니다.</p>
                <Soon label="관심 공고" />
                <Soon label="알림 설정" />
                <Soon label="계정 연결·탈퇴" />
            </Card>
            {user?.role === 'ADMIN' && (
                <Link to="/admin">관리자 검수 화면</Link>
            )}
            {logout.error && <p role="alert">{logout.error.message}</p>}
            {user && (
                <Button
                    disabled={logout.isPending}
                    onClick={() => logout.mutate()}
                >
                    로그아웃
                </Button>
            )}
        </>
    );
}

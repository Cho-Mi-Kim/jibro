import { TrialEntry } from '../features/auth/TrialEntry';
import { useSessionEntry } from '../features/auth/useSessionEntry';
import { SocialLogo } from '../shared/ui/SocialLogo';
import { cx } from '../shared/styles/classes';
import { safeReturn } from '../shared/api/returnPath';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import { demoMode } from '../shared/api/transport';
import { pagesDemo } from '../shared/api/environment';
import { Button, Card, Heading, State } from '../shared/ui/ui';
export function LoginPage({ signup = false }: { signup?: boolean }) {
    const providers = useQuery({
        queryKey: ['auth', 'providers'],
        queryFn: api.providers,
        enabled: !demoMode,
    });
    const [params] = useSearchParams();
    const [error, setError] = useState('');
    const returnTo = safeReturn(params.get('returnTo') ?? '/my');
    const mutation = useSessionEntry(returnTo, signup);
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
        if (signup && password !== String(form.get('confirmPassword') ?? '')) {
            setError('비밀번호 확인이 일치하지 않습니다.');
            return;
        }
        mutation.mutate({ name, email, password });
    }
    function social(provider: string) {
        sessionStorage.setItem('jibro.returnTo', returnTo);
        window.location.assign('/oauth2/authorization/' + provider);
    }
    return (
        <div className={cx('auth-page')}>
            {params.get('oauthError') && (
                <p role="alert">
                    {params.get('oauthError') === 'account'
                        ? '소셜 계정을 연결하지 못했습니다. 이메일 동의 또는 기존 계정 가입 여부를 확인해 주세요.'
                        : '소셜 인증을 완료하지 못했습니다. 다시 시도하고, 문제가 반복되면 관리자에게 문의해 주세요.'}
                </p>
            )}
            <Heading
                title={pagesDemo ? '시연 시작' : signup ? '회원가입' : '로그인'}
            >
                {pagesDemo
                    ? '가입 없이 가상 사용자로 준비 과정을 체험하세요.'
                    : '내 준비를 저장하고 다음에 이어가세요.'}
            </Heading>
            {!pagesDemo && (
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
                        {signup && (
                            <label>
                                비밀번호 확인
                                <input
                                    type="password"
                                    name="confirmPassword"
                                    autoComplete="new-password"
                                    required
                                />
                            </label>
                        )}
                        {(error || mutation.error) && (
                            <p role="alert">
                                {error || mutation.error?.message}
                            </p>
                        )}
                        <Button disabled={mutation.isPending}>
                            {mutation.isPending
                                ? '처리 중…'
                                : signup
                                  ? '가입 후 로그인'
                                  : '로그인'}
                        </Button>
                    </form>
                    {!demoMode && (
                        <div className={cx('social-login')}>
                            <p className={cx('social-divider')}>
                                또는 다른 계정으로 계속하기
                            </p>
                            <div className={cx('social-buttons')}>
                                <button
                                    type="button"
                                    className={cx(
                                        'social-button social-google',
                                    )}
                                    disabled={
                                        !providers.data?.includes('google')
                                    }
                                    onClick={() => social('google')}
                                >
                                    <SocialLogo provider="google" />
                                    <span>Google로 계속하기</span>
                                </button>
                                <button
                                    type="button"
                                    className={cx('social-button social-kakao')}
                                    disabled={
                                        !providers.data?.includes('kakao')
                                    }
                                    onClick={() => social('kakao')}
                                >
                                    <SocialLogo provider="kakao" />
                                    <span>카카오로 계속하기</span>
                                </button>
                            </div>
                            {providers.data?.length === 0 && (
                                <p>
                                    현재 소셜 로그인 연결을 준비 중입니다.
                                    이메일로 계속해 주세요.
                                </p>
                            )}
                            <State
                                error={providers.error}
                                retry={() => void providers.refetch()}
                            />
                        </div>
                    )}
                </Card>
            )}
            <TrialEntry
                onStart={() => mutation.mutate('trial')}
                pending={mutation.isPending}
            />
            {demoMode ? (
                <Card>
                    <p>
                        화면 테스트용 시연입니다. 실제 로그인이나 서버 저장은
                        하지 않습니다. 새로고침하면 시연 계정과 준비 기록이
                        초기화됩니다.
                    </p>
                    {mutation.error && (
                        <p role="alert">{mutation.error.message}</p>
                    )}
                    <Button
                        disabled={mutation.isPending}
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
                        disabled={mutation.isPending}
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
            ) : null}
            {!pagesDemo && (
                <>
                    <Link
                        to={
                            (signup ? '/login' : '/signup') +
                            '?returnTo=' +
                            encodeURIComponent(returnTo)
                        }
                    >
                        {signup ? '이미 계정이 있어요' : '이메일로 회원가입'}
                    </Link>
                    <Link className={cx('text-button')} to="/recovery">
                        비밀번호 재설정
                    </Link>
                </>
            )}
        </div>
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

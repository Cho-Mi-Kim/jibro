import { SocialLogo } from '../shared/ui/SocialLogo';
import { Disc } from '../shared/ui/HousingUI';
import { Icon } from '../shared/ui/Icon';
import { cx } from '../shared/styles/classes';
import { safeReturn } from '../shared/api/returnPath';
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import { demoMode } from '../shared/api/transport';
import { pagesDemo } from '../shared/api/environment';
import { Button, Card, Heading, Soon, State } from '../shared/ui/ui';
export function LoginPage({ signup = false }: { signup?: boolean }) {
    const providers = useQuery({
        queryKey: ['auth', 'providers'],
        queryFn: api.providers,
        enabled: !demoMode,
    });
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
                    <Soon label="비밀번호 재설정" />
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
    const preparations = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user,
    });
    const completed = preparations.data?.filter(
        (p) => p.progress === 100,
    ).length;
    const ongoing = preparations.data
        ? preparations.data.length - (completed ?? 0)
        : undefined;
    return (
        <>
            <Heading title="마이" />
            <div className={cx('my-grid')}>
                <section className={cx('panel profile')}>
                    <div className={cx('avatar')}>
                        {user?.name.slice(0, 1) ?? <Icon name="user" />}
                    </div>
                    <div>
                        <h2>
                            {user
                                ? user.name + '님'
                                : '로그인하고 준비를 이어가세요'}
                        </h2>
                        {user ? (
                            <Soon label="내 정보 관리" />
                        ) : (
                            <Link className={cx('link-button')} to="/login">
                                로그인
                            </Link>
                        )}
                    </div>
                </section>
                {user && (
                    <div className={cx('my-counts')}>
                        <Link className={cx('metric')} to="/preparations">
                            <Disc name="file" />
                            <div>
                                진행 중 준비<strong>{ongoing ?? '—'}건</strong>
                            </div>
                            <Icon name="arrow" />
                        </Link>
                        <Link className={cx('metric')} to="/preparations">
                            <Disc name="check" tone="gray" />
                            <div>
                                준비 기록 완료
                                <strong>{completed ?? '—'}건</strong>
                            </div>
                            <Icon name="arrow" />
                        </Link>
                    </div>
                )}
                <section className={cx('panel my-menu')}>
                    <Link to="/preparations">
                        <Disc name="calendar" />
                        <strong>내 신청 준비</strong>
                        <Icon name="arrow" />
                    </Link>
                    {(
                        ['관심 공고', '알림 설정', '완료 여정 보관'] as const
                    ).map((label, i) => (
                        <div key={label}>
                            <Disc
                                name={
                                    (['star', 'bell', 'bookmark'] as const)[i]
                                }
                                tone="gray"
                            />
                            <Soon label={label} />
                            <Icon name="arrow" />
                        </div>
                    ))}
                    <Link to="/guide">
                        <Disc name="file" />
                        <strong>서비스 이용 안내</strong>
                        <Icon name="arrow" />
                    </Link>
                    {user?.role === 'ADMIN' && (
                        <Link to="/admin">
                            <Disc name="check" />
                            <strong>관리자 검수 화면</strong>
                            <Icon name="arrow" />
                        </Link>
                    )}
                </section>
                <div className={cx('callout my-disclaimer')}>
                    <Disc name="info" tone="amber" />
                    <ul>
                        <li>
                            준비도는 신청 자격이나 선정 가능성을 뜻하지
                            않습니다.
                        </li>
                        <li>
                            실제 신청은 운영기관 공식 사이트에서 진행됩니다.
                        </li>
                        <li>주민등록번호와 증빙 원본은 저장하지 않습니다.</li>
                    </ul>
                </div>
                {user && (
                    <div className={cx('my-logout')}>
                        <State
                            error={preparations.error}
                            retry={() => void preparations.refetch()}
                        />
                        {logout.error && (
                            <p role="alert">{logout.error.message}</p>
                        )}
                        <Button
                            className="secondary wide"
                            disabled={logout.isPending}
                            onClick={() => logout.mutate()}
                        >
                            <Icon name="logout" />
                            로그아웃
                        </Button>
                    </div>
                )}
            </div>
        </>
    );
}

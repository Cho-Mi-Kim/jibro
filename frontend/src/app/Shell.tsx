import { cx } from '../shared/styles/classes';
import { useEffect, useRef, type ReactNode } from 'react';
import { Link, NavLink, Navigate, useLocation } from 'react-router';
import { useSession } from './useSession';
import { demoMode } from '../shared/api/transport';
import { State } from '../shared/ui/ui';
import { useQuery } from '@tanstack/react-query';
import { api } from '../features/housing/api';
const tabs = [
    ['/', '홈', 'M3 10 12 2 21 10v12h-7v-8h-4v8H3z'],
    ['/notices', '공고', 'M4 2h16v20H4zm3 4v2h10V6zm0 5v2h10v-2z'],
    [
        '/preparations',
        '내 준비',
        'M3 5h6V2h6v3h6v17H3zm6 0v2h6V5zm-2 7v2h10v-2z',
    ],
    ['/guide', '가이드', 'M2 3h8l2 2 2-2h8v17h-8l-2 2-2-2H2z'],
    [
        '/my',
        '마이',
        'M12 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10M3 23v-4a9 7 0 0 1 18 0v4z',
    ],
];
export function Shell({ children }: { children: ReactNode }) {
    const meta = useQuery({
        queryKey: ['meta'],
        queryFn: api.meta,
        enabled: !demoMode,
        staleTime: Infinity,
    });
    const location = useLocation();
    const main = useRef<HTMLElement>(null);
    useEffect(() => {
        window.scrollTo(0, 0);
        main.current?.focus();
    }, [location.pathname]);
    return (
        <>
            <a className={cx('skip-link')} href="#main">
                본문으로 바로가기
            </a>
            <header className={cx('topbar')}>
                <Link className={cx('brand')} to="/">
                    <span className={cx('brand-mark')}>⌂</span>집으로
                    <span className={cx('brand-sub')}>나의 주거 준비 노트</span>
                </Link>
                <Link to="/my">내 계정 ↗</Link>
            </header>
            {demoMode && (
                <div className={cx('demo-banner')} role="status">
                    개발 시연 · 가상 공고 · 메모리 임시 저장 · 새로고침 시
                    초기화
                </div>
            )}
            {!demoMode && meta.data?.sampleData && (
                <div className={cx('demo-banner')} role="status">
                    로컬 검증 · 가상 공고 · 실제 서버 저장 · 신청에 사용할 수
                    없는 자료입니다
                </div>
            )}
            <div className={cx('workspace')}>
                <aside className={cx('desktop-intro')}>
                    <span className={cx('eyebrow')}>A PLACE TO BEGIN</span>
                    <h2>
                        처음 준비하는 집,
                        <br />
                        하나씩 함께.
                    </h2>
                    <p>
                        공고를 이해하고
                        <br />
                        나에게 필요한 준비를 기록하세요.
                    </p>
                    <div className={cx('brick-art')} aria-hidden="true">
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                        <i />
                    </div>
                    <small>조건 확인 · 서류 준비 · 일정 기록</small>
                </aside>
                <main id="main" ref={main} tabIndex={-1}>
                    {children}
                </main>
            </div>
            {!location.pathname.endsWith('/conditions/edit') &&
                !location.pathname.startsWith('/admin') && (
                    <nav className={cx('bottom-nav')} aria-label="주 메뉴">
                        {tabs.map(([to, label, path]) => (
                            <NavLink key={to} to={to} end={to === '/'}>
                                <svg viewBox="0 0 24 24" aria-hidden="true">
                                    <path d={path} />
                                </svg>
                                <span>{label}</span>
                            </NavLink>
                        ))}
                    </nav>
                )}
            <footer className={cx('footer')}>
                집으로 · 신청 준비를 위한 기록
                <br />
                실제 신청은 공식 기관에서 진행합니다.
            </footer>
        </>
    );
}
export function Protected({
    children,
    admin = false,
}: {
    children: ReactNode;
    admin?: boolean;
}) {
    const session = useSession();
    const location = useLocation();
    if (session.loading) return <State loading />;
    if (session.error)
        return (
            <State error={session.error} retry={() => void session.refresh()} />
        );
    if (!session.user)
        return (
            <Navigate
                to={
                    '/login?returnTo=' +
                    encodeURIComponent(location.pathname + location.search)
                }
                replace
            />
        );
    if (admin && session.user.role !== 'ADMIN')
        return (
            <div role="alert">
                <h1>관리자 권한이 필요합니다</h1>
                <Link to="/">홈으로 돌아가기</Link>
            </div>
        );
    return children;
}

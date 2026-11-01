import { cx } from '../shared/styles/classes';
import { useEffect, useRef, type ReactNode } from 'react';
import { Link, NavLink, Navigate, useLocation } from 'react-router';
import { useSession } from './useSession';
import { demoMode } from '../shared/api/transport';
import { Icon, type IconName } from '../shared/ui/Icon';
import { Soon } from '../shared/ui/ui';
import { State } from '../shared/ui/ui';
import { useQuery } from '@tanstack/react-query';
import { api } from '../features/housing/api';
const tabs: [string, string, IconName][] = [
    ['/', '홈', 'home'],
    ['/notices', '공고', 'file'],
    ['/preparations', '내 준비', 'check'],
    ['/guide', '가이드', 'book'],
    ['/my', '마이', 'user'],
];
function Navigation({ desktop = false }: { desktop?: boolean }) {
    return (
        <nav
            className={cx(desktop ? 'desktop-nav' : 'bottom-nav')}
            aria-label={desktop ? 'PC 주 메뉴' : '주 메뉴'}
        >
            {tabs.map(([to, label, icon]) => (
                <NavLink key={to} to={to} end={to === '/'}>
                    <Icon name={icon} />
                    <span>{label}</span>
                </NavLink>
            ))}
        </nav>
    );
}
export function Shell({ children }: { children: ReactNode }) {
    const meta = useQuery({
        queryKey: ['meta'],
        queryFn: api.meta,
        enabled: !demoMode,
        staleTime: Infinity,
    });
    const location = useLocation();
    const { user } = useSession();
    const focused = /\/conditions(?:\/edit)?$/.test(location.pathname);
    const main = useRef<HTMLElement>(null);
    useEffect(() => {
        window.scrollTo(0, 0);
        main.current?.focus({ preventScroll: true });
    }, [location.pathname]);
    return (
        <>
            <a className={cx('skip-link')} href="#main">
                본문으로 바로가기
            </a>
            <header
                className={cx(
                    'topbar' +
                        (focused ? ' focus-header' : '') +
                        (!['/', '/my'].includes(location.pathname)
                            ? ' mobile-header-hidden'
                            : ''),
                )}
            >
                <Link className={cx('brand')} to="/">
                    <Icon name="home" />
                    집으로
                </Link>
                {focused ? (
                    <Link to="/preparations">나가기</Link>
                ) : (
                    <>
                        <Navigation desktop />
                        <div className={cx('header-actions')}>
                            <span className={cx('notification')}>
                                <Icon name="bell" />
                                <Soon label="알림" />
                            </span>
                            <Link
                                className={cx('avatar')}
                                to="/my"
                                aria-label="내 계정"
                            >
                                {user?.name.slice(0, 1) ?? <Icon name="user" />}
                            </Link>
                        </div>
                    </>
                )}
            </header>
            {demoMode && (
                <div className={cx('demo-banner')} role="status">
                    개발 시연 · 가상 공고 · 메모리 임시 저장 · 새로고침 시
                    초기화
                </div>
            )}
            {!demoMode && meta.data?.sampleData && (
                <div className={cx('demo-banner')} role="status">
                    로컬 개발 환경 · 예시 공고는 실제 신청에 사용할 수 없습니다.
                    실제 공고는 공식 원문을 확인하세요.
                </div>
            )}
            <div className={cx('workspace')}>
                <main id="main" ref={main} tabIndex={-1}>
                    {children}
                </main>
            </div>
            {!focused && !location.pathname.startsWith('/admin') && (
                <Navigation />
            )}
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

import { useQuery } from '@tanstack/react-query';
import { memberApi } from '../features/member/memberApi';
import { ForegroundAlerts } from '../features/member/ForegroundAlerts';
import { AlertSettingsControl } from '../features/member/AlertSettingsControl';
import { bindClasses } from '../shared/styles/classes';
import { useEffect, useRef, type ReactNode } from 'react';
import {
    Link,
    NavLink,
    Navigate,
    useLocation,
    useNavigate,
    useNavigationType,
} from 'react-router';
import { useSession } from '../features/auth/useSession';
import { demoMode } from '../shared/api/transport';
import { Icon, type IconName } from '../shared/ui/Icon';
import { State } from '../shared/ui/ui';
import { getPreparationTab } from '../features/preparation/preparationTabRoutes';
import { getHomeReturn } from '../shared/navigation/useNoticeEntryState';
import shellClasses from './Shell.module.css';
import { SiteFooter } from '../features/site/SiteFooter';

const cx = bindClasses(shellClasses);

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
    const location = useLocation();
    const navigate = useNavigate();
    const navigationType = useNavigationType();
    const { user } = useSession();
    const inbox = useQuery({
        queryKey: ['alerts', user?.id],
        queryFn: memberApi.inbox,
        enabled: !!user,
        staleTime: 60_000,
        refetchInterval: 60_000,
    });
    const hasUnread = !!user && inbox.data?.items.some((item) => !item.read);
    const alertsHeader = location.pathname === '/alerts' && !!user;
    const inPreparationTab = !!getPreparationTab(location);
    const focused = /\/conditions\/(?:edit|result)$/.test(location.pathname);
    const resultHeader =
        !!user && location.pathname.endsWith('/conditions/result');
    const homeReturn = getHomeReturn(location.state);
    const main = useRef<HTMLElement>(null);
    const previousTab = useRef(false);
    const previousPath = useRef<string | null>(null);
    useEffect(() => {
        if (
            previousPath.current === location.pathname &&
            previousTab.current === inPreparationTab
        )
            return;
        const returningFromAlerts =
            previousPath.current === '/alerts' && navigationType === 'POP';
        const restoringList = main.current?.querySelector(
            '[data-restored-list]',
        );
        previousPath.current = location.pathname;
        const switchingTabs = previousTab.current && inPreparationTab;
        previousTab.current = inPreparationTab;
        if (switchingTabs && main.current?.contains(document.activeElement))
            return;
        if (!returningFromAlerts && !restoringList) window.scrollTo(0, 0);
        main.current?.focus({ preventScroll: true });
    }, [location.pathname, inPreparationTab, navigationType]);
    return (
        <>
            <a
                className={cx('skip-link')}
                href="#main"
                onClick={(event) => {
                    event.preventDefault();
                    main.current?.focus();
                    main.current?.scrollIntoView();
                }}
            >
                본문으로 바로가기
            </a>
            <ForegroundAlerts inbox={inbox.data} />
            <header
                data-guide-header={location.pathname === '/guide' || undefined}
                data-notice-header={
                    location.pathname === '/notices' || undefined
                }
                className={cx(
                    'topbar' +
                        (location.pathname === '/notices'
                            ? ' notice-sticky-header'
                            : '') +
                        (focused ? ' focus-header' : '') +
                        (!inPreparationTab &&
                        ![
                            '/',
                            '/my',
                            '/notices',
                            '/preparations',
                            '/guide',
                        ].includes(location.pathname) &&
                        !alertsHeader &&
                        !resultHeader
                            ? ' mobile-header-hidden'
                            : ''),
                )}
            >
                {resultHeader ? (
                    <div className={cx('brand screen-title')}>
                        <Link
                            to={
                                homeReturn ??
                                location.pathname.replace(
                                    /\/conditions\/result$/,
                                    '',
                                )
                            }
                            replace={!!homeReturn}
                            aria-label="이전 화면으로"
                        >
                            <Icon name="back" />
                        </Link>
                        <h1>내 조건 확인 결과</h1>
                    </div>
                ) : alertsHeader ? (
                    <div className={cx('brand screen-title')}>
                        <button
                            type="button"
                            aria-label="이전 화면으로"
                            onClick={() => {
                                // A push has a predecessor; the router index also survives reloads.
                                if (
                                    navigationType === 'PUSH' ||
                                    window.history.state?.idx > 0
                                ) {
                                    navigate(-1);
                                } else {
                                    navigate('/my', { replace: true });
                                }
                            }}
                        >
                            <Icon name="back" />
                        </button>
                        <h1>알림</h1>
                    </div>
                ) : (
                    <Link className={cx('brand')} to="/">
                        <Icon name="home" /> 집으로
                    </Link>
                )}
                {focused ? (
                    !resultHeader && <Link to="/preparations">나가기</Link>
                ) : (
                    <>
                        <Navigation desktop />
                        <div className={cx('header-actions')}>
                            {alertsHeader ? (
                                <AlertSettingsControl />
                            ) : (
                                <>
                                    <Link
                                        className={cx('notification')}
                                        aria-label={
                                            hasUnread
                                                ? '알림 확인, 읽지 않은 알림 있음'
                                                : '알림 확인'
                                        }
                                        data-unread={hasUnread || undefined}
                                        to="/alerts"
                                    >
                                        <Icon name="bell" />
                                    </Link>
                                    {location.pathname === '/my' ? (
                                        <Link
                                            className={cx('avatar my-settings')}
                                            to="/settings"
                                            aria-label="내 정보 관리"
                                            title="내 정보 관리"
                                        >
                                            <Icon name="settings" />
                                        </Link>
                                    ) : (
                                        <Link
                                            className={cx('avatar')}
                                            to="/my"
                                            aria-label="내 계정"
                                        >
                                            {user?.trial ? (
                                                <Icon name="user" />
                                            ) : (
                                                (user?.name.slice(0, 1) ?? (
                                                    <Icon name="user" />
                                                ))
                                            )}
                                        </Link>
                                    )}
                                </>
                            )}
                        </div>
                    </>
                )}
            </header>
            {demoMode && (
                <div className={cx('demo-banner')} role="status">
                    화면 테스트용 시연 · 가상 공고 · 서버 저장 없음 · 새로고침
                    시 초기화
                </div>
            )}
            <div className={cx('workspace')}>
                <main id="main" ref={main} tabIndex={-1}>
                    {children}
                </main>
            </div>
            {!focused && !location.pathname.startsWith('/admin') && (
                <SiteFooter />
            )}
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
                    encodeURIComponent(
                        location.pathname + location.search + location.hash,
                    )
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

import { MyProfileSummary } from '../features/member/MyProfileSummary';
import { AlertSettingsControl } from '../features/member/AlertSettingsControl';
import { disconnectThisDevice } from '../features/member/browserDevice';
import { memberApi } from '../features/member/memberApi';
import { Disc } from '../shared/ui/HousingUI';
import { Icon } from '../shared/ui/Icon';
import { cx } from '../shared/styles/classes';
import { Link, useNavigate } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import { Button, State } from '../shared/ui/ui';
export function MyPage() {
    const { user } = useSession();
    const client = useQueryClient();
    const navigate = useNavigate();
    const logout = useMutation({
        mutationFn: async () => {
            if (!user?.trial) await disconnectThisDevice();
            await api.logout();
        },
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
    const archive = useQuery({
        queryKey: ['archive', user?.id],
        queryFn: memberApi.archive,
        enabled: !!user,
    });
    const ongoing = preparations.data?.length;
    return (
        <>
            <h1 className={cx('visually-hidden')}>마이</h1>
            <div className={cx('my-grid')}>
                <MyProfileSummary
                    user={user}
                    ongoing={ongoing}
                    archived={archive.data?.length}
                />
                <section className={cx('panel my-menu')}>
                    <Link to="/preparations">
                        <Disc name="calendar" />
                        <strong>내 신청 준비</strong>
                        <Icon name="arrow" />
                    </Link>
                    {(
                        [
                            ['관심 공고', '/saved', 'star'],
                            ['알림 설정', '/alerts', 'bell'],
                            ['완료 여정 보관', '/archive', 'bookmark'],
                        ] as const
                    ).map(([label, to, icon]) =>
                        to === '/alerts' ? (
                            <AlertSettingsControl
                                key={to}
                                className={cx('my-alert-settings')}
                            >
                                <Disc name={icon} tone="gray" />
                                <strong>{label}</strong>
                                <Icon name="arrow" />
                            </AlertSettingsControl>
                        ) : (
                            <Link key={to} to={to}>
                                <Disc name={icon} tone="gray" />
                                <strong>{label}</strong>
                                <Icon name="arrow" />
                            </Link>
                        ),
                    )}
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
                            {user.trial ? '로그아웃 및 기록 삭제' : '로그아웃'}
                        </Button>
                    </div>
                )}
            </div>
            <footer className={cx('my-footer')}>
                집으로 · 개인 맞춤형 청년 주거지원 준비 관리
            </footer>
        </>
    );
}

import { PreparationIdentity } from '../features/preparation/PreparationIdentity';
import { NextAction } from '../features/preparation/NextAction';
import { useSessionEntry } from '../features/auth/useSessionEntry';
import { TrialEntry } from '../features/auth/TrialEntry';
import { useNotice } from '../shared/ui/housingHelpers';
import { cx } from '../shared/styles/classes';
import { Link } from 'react-router';
import { NoticeHomeSummary } from '../features/home/NoticeHomeSummary';
import { noticeHomePath } from '../shared/navigation/homeNotice';
import { useHomeNotice } from '../features/home/useHomeNotice';
import { useSession } from '../app/useSession';
import { Card, Heading, State } from '../shared/ui/ui';
import {
    Deadline,
    Disc,
    Journey,
    Metrics,
    PreparationSummary,
} from '../shared/ui/HousingUI';
import type { Preparation } from '../features/housing/types';
import { Icon } from '../shared/ui/Icon';
function HomeDashboard({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <div className={cx('home-dashboard')}>
            <div className={cx('home-summary')}>
                <PreparationSummary
                    p={p}
                    identity={
                        <PreparationIdentity preparation={p} notice={n.data} />
                    }
                />
            </div>
            <div className={cx('home-action')}>
                <NextAction p={p} />
            </div>
            <div className={cx('home-journey')}>
                <Journey p={p} />
            </div>
            <div className={cx('home-metrics')}>
                <Metrics p={p} schedule />
            </div>
            <aside className={cx('panel home-deadline')}>
                <Deadline date={n.data?.endDate}>
                    <Link
                        className={cx('link-button outline-button wide')}
                        to={'/preparations/' + p.id + '/schedule'}
                    >
                        신청 일정 보기 <Icon name="arrow" />
                    </Link>
                </Deadline>
            </aside>
        </div>
    );
}
export function HomePage() {
    const { user, loading, error, refresh } = useSession();
    const {
        preparations: q,
        active: p,
        noticeId,
        notice,
        selectNotice,
    } = useHomeNotice();
    const trial = useSessionEntry(noticeId ? noticeHomePath(noticeId) : '/my');
    const entry = !user && (
        <>
            <TrialEntry
                onStart={() => {
                    if (noticeId) selectNotice(noticeId);
                    trial.mutate('trial');
                }}
                pending={trial.isPending}
            />
            {trial.error && <p role="alert">{trial.error.message}</p>}
        </>
    );
    return (
        <>
            <div className={cx('home-heading')}>
                <Heading title="홈" />
            </div>
            <State
                loading={loading || (!!user && q.isPending)}
                error={error || q.error}
                retry={() => {
                    if (error) void refresh();
                    else void q.refetch();
                }}
            />
            {p && !error ? (
                <HomeDashboard p={p} />
            ) : noticeId ? (
                <>
                    <State
                        loading={notice.isPending}
                        error={notice.error}
                        retry={() => void notice.refetch()}
                    />
                    {notice.data && (
                        <NoticeHomeSummary notice={notice.data}>
                            {entry}
                        </NoticeHomeSummary>
                    )}
                    {notice.isError && (
                        <button
                            className={cx('text-button')}
                            onClick={() => selectNotice(null)}
                        >
                            기본 홈으로 돌아가기
                        </button>
                    )}
                </>
            ) : (
                !loading &&
                !error &&
                !q.error &&
                (!user || !q.isPending) && (
                    <>
                        <section className={cx('panel welcome')}>
                            <Disc name="home" />
                            <h1>
                                나의 첫 집을 향해,
                                <br />
                                하나씩 준비해요.
                            </h1>
                            <p>
                                공고를 이해하고, 필요한 서류와 일정을 한곳에.
                                <br />
                                집으로가 신청 준비를 함께합니다.
                            </p>
                            <Link className={cx('link-button')} to="/notices">
                                공고 둘러보기 <Icon name="arrow" />
                            </Link>
                            {entry}
                        </section>
                        <div className={cx('intro-grid')}>
                            {[
                                [
                                    '공고 확인',
                                    '공식 근거와 안내 상태를 확인해요.',
                                ],
                                [
                                    '내 조건 정리',
                                    '모르는 항목은 추가 확인으로 남겨요.',
                                ],
                                [
                                    '준비 이어가기',
                                    '서류와 일정을 하나씩 기록해요.',
                                ],
                            ].map(([t, d], i) => (
                                <Card key={t}>
                                    <Disc
                                        name={
                                            (
                                                [
                                                    'file',
                                                    'check',
                                                    'calendar',
                                                ] as const
                                            )[i]
                                        }
                                    />
                                    <h2>{t}</h2>
                                    <p>{d}</p>
                                </Card>
                            ))}
                        </div>
                    </>
                )
            )}
        </>
    );
}

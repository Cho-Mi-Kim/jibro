import { useState } from 'react';
import { lifecycleLabel } from '../features/housing/noticeLifecycle';
import { PreparationIdentity } from '../features/preparation/PreparationIdentity';
import { NextAction } from '../features/preparation/NextAction';
import { useNotice } from '../features/housing/useNotice';
import { bindClasses } from '../shared/styles/classes';
import { Link } from 'react-router';
import { NoticeHomeSummary } from '../features/home/NoticeHomeSummary';
import { HomeNoticeTitle } from '../features/home/HomeNoticeTitle';
import { useHomeNotice } from '../features/home/useHomeNotice';
import { useSession } from '../features/auth/useSession';
import { Card, Heading, State } from '../shared/ui/ui';
import { Deadline } from '../features/housing/NoticeFacts';
import { Disc } from '../shared/ui/Disc';
import {
    Journey,
    Metrics,
    PreparationSummary,
} from '../features/preparation/PreparationUI';
import type { Preparation } from '../features/housing/types';
import { Icon } from '../shared/ui/Icon';
import homeClasses from '../features/home/Home.module.css';

const cx = bindClasses(homeClasses);

function HomeDashboard({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    const [expanded, setExpanded] = useState(false);
    return (
        <div className={cx('home-dashboard')}>
            <div className={cx('home-summary')}>
                <PreparationSummary
                    p={p}
                    onExplanationToggle={setExpanded}
                    identity={
                        <PreparationIdentity
                            preparation={p}
                            notice={n.data}
                            titleContent={
                                <HomeNoticeTitle
                                    title={p.title}
                                    expanded={expanded}
                                />
                            }
                        />
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
                <Deadline
                    date={n.data?.endDate}
                    statusLabel={lifecycleLabel(n.data) || undefined}
                >
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
        preparation,
        hasPreparation,
    } = useHomeNotice();
    return (
        <>
            <div className={cx('home-heading')}>
                <Heading title="홈" />
            </div>
            <State
                loading={
                    loading ||
                    (!!user && q.isPending) ||
                    (hasPreparation && preparation.isPending)
                }
                error={error || q.error || preparation.error}
                retry={() => {
                    if (error) void refresh();
                    else {
                        void q.refetch();
                        if (hasPreparation) void preparation.refetch();
                    }
                }}
            />
            {p && !error ? (
                <HomeDashboard key={p.noticeId} p={p} />
            ) : hasPreparation ? null : noticeId ? (
                <>
                    <State
                        loading={notice.isPending}
                        error={notice.error}
                        retry={() => void notice.refetch()}
                    />
                    {notice.data && <NoticeHomeSummary notice={notice.data} />}
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
                                공고를 이해하고,
                                <br />
                                필요한 서류와 일정을 한곳에.
                                <br />
                                집으로가 신청 준비를 함께합니다.
                            </p>
                            <Link className={cx('link-button')} to="/notices">
                                공고 둘러보기 <Icon name="arrow" />
                            </Link>
                        </section>
                        <Card>
                            <ol
                                className={cx('home-intro')}
                                aria-label="집으로 이용 순서"
                                role="list"
                            >
                                {(
                                    [
                                        {
                                            icon: 'file',
                                            title: '공고 확인',
                                            description:
                                                '공식 근거와 안내 상태를 확인해요.',
                                        },
                                        {
                                            icon: 'check',
                                            title: '내 조건 정리',
                                            description:
                                                '모르는 항목은 추가 확인으로 남겨요.',
                                        },
                                        {
                                            icon: 'calendar',
                                            title: '준비 이어가기',
                                            description:
                                                '서류와 일정을 하나씩 기록해요.',
                                        },
                                    ] as const
                                ).map(({ icon, title, description }) => (
                                    <li key={title}>
                                        <Disc name={icon} />
                                        <div>
                                            <h2>{title}</h2>
                                            <p>{description}</p>
                                        </div>
                                    </li>
                                ))}
                            </ol>
                        </Card>
                    </>
                )
            )}
        </>
    );
}

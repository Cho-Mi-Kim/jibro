import { useNotice } from '../shared/ui/housingHelpers';
import { cx } from '../shared/styles/classes';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../app/useSession';
import { api } from '../features/housing/api';
import { Card, EvidenceLink, Heading, Soon, State } from '../shared/ui/ui';
import {
    Deadline,
    Disc,
    Journey,
    Metrics,
    NextAction,
    PreparationSummary,
} from '../shared/ui/HousingUI';
import type { Preparation } from '../features/housing/types';
import { Icon, type IconName } from '../shared/ui/Icon';
function HomeDashboard({ p }: { p: Preparation }) {
    const n = useNotice(p.noticeId);
    return (
        <div className={cx('home-dashboard')}>
            <div className={cx('home-summary')}>
                <PreparationSummary p={p} />
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
    const { user, loading, error } = useSession();
    const q = useQuery({
        queryKey: ['preparations', user?.id],
        queryFn: api.preparations,
        enabled: !!user,
    });
    const p = q.data?.[0];
    return (
        <>
            <div className={cx('home-heading')}>
                <Heading title="홈" />
            </div>
            <State
                loading={loading || (!!user && q.isPending)}
                error={error || q.error}
                retry={() => void q.refetch()}
            />
            {p ? (
                <HomeDashboard p={p} />
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
const categories: [string, IconName, string][] = [
    ['지원제도 알아보기', 'home', 'red'],
    ['조건과 용어', 'file', 'amber'],
    ['서류 발급 안내', 'file', 'green'],
    ['신청 절차', 'calendar', 'red'],
    ['공식 문의처', 'phone', 'amber'],
];
export function GuidePage() {
    const [params, setParams] = useSearchParams();
    const q = useQuery({ queryKey: ['guides'], queryFn: api.guides });
    const category = params.get('category');
    const selected = q.data?.filter(
        (g) => !category || g.category === category,
    );
    return (
        <>
            <header className={cx('guide-heading')}>
                <Heading title="가이드" />
                <div className={cx('guide-search')}>
                    <Icon name="search" />
                    <Soon label="가이드 검색" />
                    <div className={cx('guide-art')} aria-hidden="true">
                        <Icon name="file" />
                        <i />
                        <i />
                        <i />
                    </div>
                </div>
            </header>
            <div className={cx('mobile-only guide-mobile-search')}>
                <Icon name="search" />
                <Soon label="가이드 검색" />
            </div>
            <div className={cx('guide-layout')}>
                <aside>
                    <h2 className={cx('mobile-only')}>주제별 가이드</h2>
                    <nav
                        className={cx('guide-categories')}
                        aria-label="가이드 주제"
                    >
                        {categories.map(([label, icon, tone]) => (
                            <button
                                key={label}
                                aria-pressed={category === label}
                                onClick={() =>
                                    setParams(
                                        category === label
                                            ? {}
                                            : { category: label },
                                    )
                                }
                            >
                                <Disc name={icon} tone={tone} />
                                <strong>{label}</strong>
                                <Icon name="arrow" />
                            </button>
                        ))}
                    </nav>
                </aside>
                <section>
                    <h2>자주 확인하는 질문</h2>
                    <State
                        loading={q.isPending}
                        error={q.error}
                        retry={() => void q.refetch()}
                    />
                    {selected?.map((g, i) => (
                        <details
                            className={cx('card faq')}
                            key={g.id}
                            open={i === 0}
                        >
                            <summary>
                                <Disc
                                    name={i === 1 ? 'people' : 'file'}
                                    tone={i === 1 ? 'amber' : 'red'}
                                />
                                <span>{g.title}</span>
                                <Icon name="down" />
                            </summary>
                            <p>{g.body}</p>
                            <EvidenceLink evidence={g.evidence} />
                        </details>
                    ))}
                    {selected?.length === 0 && (
                        <Card>
                            <h3>이 주제의 가이드를 준비하고 있어요.</h3>
                            <button
                                className={cx('text-button')}
                                onClick={() => setParams({})}
                            >
                                전체 가이드 보기
                            </button>
                        </Card>
                    )}
                    <div className={cx('callout')}>
                        <Disc name="warning" tone="amber" />
                        <div>
                            <strong>
                                일반 안내와 공고별 기준은 다를 수 있어요.
                            </strong>
                            <p>선택한 공고의 공식 근거를 함께 확인해 주세요.</p>
                        </div>
                    </div>
                </section>
            </div>
        </>
    );
}

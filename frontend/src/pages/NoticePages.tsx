import { cx } from '../shared/styles/classes';
import { guideLabels } from '../shared/ui/helpers';
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { useSession } from '../app/useSession';
import {
    Button,
    Card,
    EvidenceLink,
    Heading,
    State,
    Soon,
} from '../shared/ui/ui';
import {
    Deadline,
    Disc,
    displayDate,
    GuideBadge,
    NoticeDates,
    OfficialLink,
    Status,
} from '../shared/ui/HousingUI';
import { Icon } from '../shared/ui/Icon';
import type { Notice } from '../features/housing/types';
const statusLabels = {
    OPEN: '접수 중',
    UPCOMING: '접수 예정',
    CLOSED: '접수 종료',
    UNKNOWN: '일정 확인 필요',
};
export function NoticeCard({ notice: n }: { notice: Notice }) {
    return (
        <article className={cx('panel notice-card')}>
            <div className={cx('row')}>
                <Status tone={n.status === 'OPEN' ? 'red' : 'gray'}>
                    {statusLabels[n.status]}
                </Status>
                <GuideBadge state={n.guideState} />
            </div>
            <div className={cx('notice-card-main')}>
                <div>
                    <Link
                        className={cx('card-title')}
                        to={'/notices/' + n.id}
                        aria-label={n.title + ' ↗'}
                    >
                        {n.title}
                    </Link>
                    <p className={cx('muted')}>
                        {n.agency}　|　{n.region}　|　{n.type}
                    </p>
                </div>
                <span className={cx('bookmark-action')}>
                    <Icon name="bookmark" />
                    <Soon label="관심 공고" />
                </span>
                <Link
                    to={'/notices/' + n.id}
                    aria-label={n.title + ' 상세 보기'}
                >
                    <Icon name="arrow" />
                </Link>
            </div>
            <NoticeDates notice={n} />
        </article>
    );
}
export function NoticesPage() {
    const [params, setParams] = useSearchParams();
    const filters = useQuery({
        queryKey: ['notice-filters'],
        queryFn: api.filters,
    });
    const normalized = new URLSearchParams(params);
    const page = Math.max(0, Math.floor(Number(params.get('page')) || 0));
    normalized.set('page', String(page));
    normalized.set('size', '20');
    const query = useQuery({
        queryKey: ['notices', normalized.toString()],
        queryFn: () => api.notices(normalized.toString()),
    });
    function change(key: string, value: string, multiple = false) {
        const next = new URLSearchParams(params);
        if (multiple) {
            const values = next.getAll(key);
            next.delete(key);
            (values.includes(value)
                ? values.filter((v) => v !== value)
                : [...values, value]
            ).forEach((v) => next.append(key, v));
        } else if (value) next.set(key, value);
        else next.delete(key);
        next.set('page', '0');
        setParams(next);
    }
    function search(e: FormEvent<HTMLFormElement>) {
        e.preventDefault();
        change(
            'keyword',
            String(new FormData(e.currentTarget).get('keyword') ?? ''),
        );
    }
    const options: [string, string, string[]][] = [
        ['region', '지역', filters.data?.regions ?? []],
        ['type', '지원 유형', filters.data?.types ?? []],
        ['status', '접수 상태', ['OPEN', 'UPCOMING', 'CLOSED', 'UNKNOWN']],
    ];
    return (
        <div className={cx('notice-search-page')}>
            <Heading title="공고 찾기" />
            <form className={cx('search')} onSubmit={search}>
                <Icon name="search" />
                <input
                    key={params.get('keyword')}
                    name="keyword"
                    aria-label="공고 검색"
                    placeholder="공고명이나 기관을 검색하세요."
                    defaultValue={params.get('keyword') ?? ''}
                />
                <button aria-label="검색" type="submit">
                    <Icon name="arrow" />
                </button>
            </form>
            <aside className={cx('filter-panel')}>
                <h2>검색 조건</h2>
                {options.map(([key, label, values]) => (
                    <details key={key} className={cx('filter-group')} open>
                        <summary>
                            {label} <Icon name="down" />
                        </summary>
                        <div>
                            {values.map((value) => (
                                <label className={cx('chip')} key={value}>
                                    <input
                                        type="checkbox"
                                        checked={params
                                            .getAll(key)
                                            .includes(value)}
                                        onChange={() =>
                                            change(key, value, true)
                                        }
                                    />
                                    {statusLabels[
                                        value as keyof typeof statusLabels
                                    ] ?? value}
                                </label>
                            ))}
                        </div>
                    </details>
                ))}
                <button
                    className={cx('link-button outline-button wide')}
                    onClick={() => setParams({})}
                >
                    필터 초기화
                </button>
                {filters.error && (
                    <State
                        error={filters.error}
                        retry={() => void filters.refetch()}
                    />
                )}
            </aside>
            <section className={cx('notice-results')}>
                <div className={cx('row results-heading')}>
                    <h2>
                        공고 <em>{query.data?.totalElements ?? '—'}건</em>
                    </h2>
                    <div className={cx('row')}>
                        <select
                            aria-label="공고 정렬"
                            value={params.get('sort') ?? 'deadline'}
                            onChange={(e) => change('sort', e.target.value)}
                        >
                            <option value="deadline">마감 가까운 순</option>
                            <option value="newest">최신순</option>
                        </select>
                        <Soon label="공고 비교" />
                    </div>
                </div>
                <State
                    loading={query.isPending}
                    error={query.error}
                    retry={() => void query.refetch()}
                />
                {query.data?.items.map((n) => (
                    <NoticeCard key={n.id} notice={n} />
                ))}
                {query.data?.items.length === 0 && (
                    <Card>
                        <h2>조건에 맞는 공고가 없어요</h2>
                        <Button onClick={() => setParams({})}>
                            검색 조건 초기화
                        </Button>
                    </Card>
                )}
                <p className={cx('muted info-line')}>
                    <Icon name="info" />
                    접수 상태와 준비 안내 제공 여부는 별개입니다.
                </p>
                <nav className={cx('pagination')} aria-label="공고 페이지">
                    <button
                        disabled={page === 0}
                        aria-label="이전 페이지"
                        onClick={() => {
                            normalized.set('page', String(page - 1));
                            setParams(normalized);
                        }}
                    >
                        <Icon name="back" />
                    </button>
                    <span>
                        {page + 1} / {Math.max(1, query.data?.totalPages ?? 1)}
                    </span>
                    <button
                        disabled={
                            !query.data || page + 1 >= query.data.totalPages
                        }
                        aria-label="다음 페이지"
                        onClick={() => {
                            normalized.set('page', String(page + 1));
                            setParams(normalized);
                        }}
                    >
                        <Icon name="arrow" />
                    </button>
                </nav>
            </section>
        </div>
    );
}
export function NoticeDetailPage() {
    const { noticeId = '' } = useParams();
    const session = useSession();
    const navigate = useNavigate();
    const client = useQueryClient();
    const [tab, setTab] = useState('핵심 요약');
    const query = useQuery({
        queryKey: ['notice', noticeId],
        queryFn: () => api.notice(noticeId),
    });
    const start = useMutation({
        mutationFn: () => api.start(noticeId, query.data!.version),
        onSuccess: (p) => {
            void client.invalidateQueries({ queryKey: ['preparations'] });
            navigate('/preparations/' + p.id + '/conditions/edit');
        },
    });
    const n = query.data;
    return (
        <>
            <Link className={cx('breadcrumb')} to="/notices">
                <Icon name="back" />
                공고 <Icon name="arrow" />
                공고 상세
            </Link>
            <State
                loading={query.isPending}
                error={query.error}
                retry={() => void query.refetch()}
            />
            {n && (
                <div className={cx('notice-detail')}>
                    <header className={cx('notice-detail-heading')}>
                        <Heading title={n.title}>
                            {n.agency} · {n.region} · 공고일{' '}
                            {displayDate(n.publishedDate)}
                        </Heading>
                    </header>
                    <aside className={cx('panel notice-detail-aside')}>
                        <div className={cx('row')}>
                            <Status
                                tone={n.status === 'OPEN' ? 'green' : 'gray'}
                            >
                                {statusLabels[n.status]}
                            </Status>
                            <GuideBadge state={n.guideState} />
                        </div>
                        <Deadline date={n.endDate} />
                        <NoticeDates notice={n} />
                        <div className={cx('divider')} />
                        <div className={cx('notice-facts')}>
                            <Icon name="pin" />
                            <div>
                                <small>지역</small>
                                <strong>{n.region}</strong>
                            </div>
                            <Icon name="building" />
                            <div>
                                <small>유형</small>
                                <strong>{n.type}</strong>
                            </div>
                        </div>
                        {start.error && (
                            <p role="alert">{start.error.message}</p>
                        )}
                        {n.canStart && (
                            <Button
                                className="wide"
                                disabled={start.isPending || session.loading}
                                onClick={() =>
                                    session.user
                                        ? start.mutate()
                                        : navigate(
                                              '/login?returnTo=' +
                                                  encodeURIComponent(
                                                      '/notices/' + noticeId,
                                                  ),
                                          )
                                }
                            >
                                {start.isPending
                                    ? '준비 건 만드는 중…'
                                    : '내 조건 확인하기'}
                            </Button>
                        )}
                        <OfficialLink
                            url={n.evidence.url}
                            className="link-button outline-button wide"
                        >
                            공식 공고 보기
                        </OfficialLink>
                        <p className={cx('muted info-line')}>
                            <Icon name="info" />
                            최종 자격은 운영기관의 심사로 결정됩니다.
                        </p>
                    </aside>
                    <section className={cx('notice-detail-content')}>
                        <div
                            className={cx('tabs')}
                            role="tablist"
                            aria-label="공고 정보"
                        >
                            {['핵심 요약', '조건', '제출서류', '일정'].map(
                                (x) => (
                                    <button
                                        role="tab"
                                        aria-selected={x === tab}
                                        key={x}
                                        onClick={() => setTab(x)}
                                    >
                                        {x}
                                    </button>
                                ),
                            )}
                        </div>
                        {tab === '핵심 요약' ? (
                            <>
                                <section className={cx('panel summary-card')}>
                                    <Disc name="file" />
                                    <div>
                                        <h2>어떤 지원인가요?</h2>
                                        <p>{n.summary}</p>
                                        <EvidenceLink evidence={n.evidence} />
                                    </div>
                                </section>
                                <section className={cx('panel summary-card')}>
                                    <Disc name="file" />
                                    <div>
                                        <h2>자격과 순위를 구분해요</h2>
                                        <p>
                                            신청 자격과 선정 순위는 서로 다른
                                            기준이에요.
                                        </p>
                                        <button
                                            className={cx('text-button')}
                                            onClick={() => setTab('조건')}
                                        >
                                            조건과 공식 근거 확인{' '}
                                            <Icon name="arrow" />
                                        </button>
                                    </div>
                                </section>
                                <section className={cx('panel summary-card')}>
                                    <Disc name="file" />
                                    <div>
                                        <h2>필요한 서류 확인</h2>
                                        <p>
                                            공통 서류와 해당자 제출 서류를
                                            나누어 확인해요.
                                        </p>
                                        <button
                                            className={cx('text-button')}
                                            onClick={() => setTab('제출서류')}
                                        >
                                            제출서류 확인 <Icon name="arrow" />
                                        </button>
                                    </div>
                                </section>
                            </>
                        ) : tab === '일정' ? (
                            <Card>
                                <h2>공식 접수 일정</h2>
                                <NoticeDates notice={n} />
                                <EvidenceLink evidence={n.evidence} />
                            </Card>
                        ) : n.guideState !== 'AVAILABLE' ? (
                            <Card>
                                <p>
                                    {guideLabels[n.guideState]}입니다. 검수되지
                                    않은 개인별 안내는 제공하지 않습니다.
                                </p>
                            </Card>
                        ) : tab === '조건' ? (
                            n.questions.map((q) => (
                                <Card key={q.id}>
                                    <h2>{q.title}</h2>
                                    <p>{q.reason}</p>
                                    <EvidenceLink evidence={q.evidence} />
                                </Card>
                            ))
                        ) : (
                            <Card>
                                <h2>제출서류</h2>
                                {n.documents.map((d) => (
                                    <p key={d}>{d}</p>
                                ))}
                                <EvidenceLink evidence={n.evidence} />
                            </Card>
                        )}
                        {n.attachments.length > 0 && (
                            <Card>
                                <h2>공고 첨부 자료</h2>
                                {n.attachments.map((e, i) => (
                                    <EvidenceLink key={i} evidence={e} />
                                ))}
                            </Card>
                        )}
                    </section>
                </div>
            )}
        </>
    );
}

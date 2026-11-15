import { bindClasses } from '../shared/styles/classes';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { api } from '../features/housing/api';
import { noticeRefreshInterval } from '../features/housing/noticeRefresh';
import { Button, Card, State } from '../shared/ui/ui';
import { Icon } from '../shared/ui/Icon';
import { NoticeCard } from '../features/housing/NoticeCard';
import { NoticeSort } from '../features/housing/NoticeSort';
import { DEFAULT_NOTICE_SORT } from '../features/housing/noticeOrdering';
import { NoticeFilters } from '../features/housing/NoticeFilters';
import styles from '../features/housing/NoticeList.module.css';
import { useNoticeToolbarOffset } from '../features/housing/useNoticeToolbarOffset';
import { useNoticeToolbarReveal } from '../features/housing/useNoticeToolbarReveal';
import housingClasses from '../features/housing/NoticeFacts.module.css';

const cx = bindClasses(housingClasses);

export function NoticesPage() {
    const controls = useRef<HTMLDivElement>(null);
    useNoticeToolbarOffset(controls);
    const [searchParams, setParams] = useSearchParams();
    const params = new URLSearchParams(searchParams);
    const regions = params.getAll('region');
    if (regions.some((region) => region.endsWith(' 외'))) {
        params.delete('region');
        for (const region of new Set(
            regions.map((value) => value.replace(/ 외$/, '')),
        )) {
            params.append('region', region);
        }
    }
    const [preview, setPreview] = useState<{
        id: string;
        search: string;
    } | null>(null);
    const filters = useQuery({
        queryKey: ['notice-filters'],
        queryFn: api.filters,
        staleTime: 60_000,
    });
    const normalized = new URLSearchParams(params);
    normalized.delete('appearance');
    const sort = params.get('sort') || DEFAULT_NOTICE_SORT;
    normalized.set('sort', sort);
    const page = Math.max(0, Math.floor(Number(params.get('page')) || 0));
    normalized.set('page', String(page));
    normalized.set('size', '20');
    const query = useQuery({
        queryKey: ['notices', normalized.toString()],
        queryFn: () => api.notices(normalized.toString()),
        staleTime: 15_000,
        refetchInterval: (query) =>
            Math.min(
                60_000,
                noticeRefreshInterval(query.state.data?.items ?? []) || 60_000,
            ),
    });
    const activePreview =
        preview?.search === normalized.toString() &&
        query.data?.items.some((notice) => notice.id === preview.id)
            ? preview
            : null;
    const controlsCollapsed = useNoticeToolbarReveal(activePreview);
    const { state } = useLocation();
    useEffect(() => {
        if (typeof state?.noticeAnchor !== 'string' || !query.data) return;
        const frame = requestAnimationFrame(() => {
            const card = document.getElementById(
                'notice-card-' + state.noticeAnchor,
            );
            card?.querySelector<HTMLButtonElement>('h3 button')?.focus({
                preventScroll: true,
            });
            card?.scrollIntoView({ block: 'nearest' });
        });
        return () => cancelAnimationFrame(frame);
    }, [state, query.data]);
    function change(key: string, value: string) {
        const next = new URLSearchParams(params);
        if (value) next.set(key, value);
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
    function goToPage(nextPage: number) {
        const next = new URLSearchParams(params);
        next.set('page', String(nextPage));
        setParams(next);
    }
    return (
        <div className={styles.page}>
            <div
                ref={controls}
                className={styles.controls}
                data-notice-controls
                data-collapsed={controlsCollapsed}
            >
                <div className={styles.controlsClip}>
                    <div className={styles.controlsContent}>
                        <div className={styles.heading}>
                            <h1 className={cx('visually-hidden')}>공고 찾기</h1>
                            <div className={styles.refreshRow}>
                                {query.dataUpdatedAt > 0 && (
                                    <p className={styles.loadedAt}>
                                        최종 업데이트 ·{' '}
                                        <time
                                            dateTime={new Date(
                                                query.dataUpdatedAt,
                                            ).toISOString()}
                                        >
                                            {new Intl.DateTimeFormat('ko-KR', {
                                                timeZone: 'Asia/Seoul',
                                                year: 'numeric',
                                                month: '2-digit',
                                                day: '2-digit',
                                                hour: '2-digit',
                                                minute: '2-digit',
                                                hourCycle: 'h23',
                                            }).format(query.dataUpdatedAt)}
                                        </time>
                                    </p>
                                )}
                                <button
                                    type="button"
                                    className={styles.refreshButton}
                                    aria-label="공고 새로고침"
                                    title="새로고침"
                                    disabled={query.isFetching}
                                    aria-busy={query.isFetching}
                                    onClick={() => void query.refetch()}
                                >
                                    <svg
                                        width="20"
                                        height="20"
                                        viewBox="0 0 24 24"
                                        fill="none"
                                        stroke="currentColor"
                                        strokeWidth="1.8"
                                        strokeLinecap="round"
                                        strokeLinejoin="round"
                                        aria-hidden="true"
                                    >
                                        <path d="M20 4v6h-6M20 10a8 8 0 1 0 .1 4" />
                                    </svg>
                                </button>
                            </div>
                        </div>
                        <form
                            className={styles.search}
                            role="search"
                            onSubmit={search}
                        >
                            <button aria-label="검색" type="submit">
                                <Icon name="search" />
                            </button>
                            <input
                                key={params.get('keyword')}
                                name="keyword"
                                aria-label="공고 검색"
                                placeholder="공고명이나 기관을 검색하세요."
                                defaultValue={params.get('keyword') ?? ''}
                            />
                        </form>
                        <NoticeFilters
                            regions={filters.data?.regions ?? []}
                            types={filters.data?.types ?? []}
                            params={params}
                            onApply={setParams}
                        />
                        {filters.error && (
                            <State
                                error={filters.error}
                                retry={() => void filters.refetch()}
                            />
                        )}
                        <div className={styles.toolbar}>
                            <h2>
                                공고{' '}
                                <em>{query.data?.totalElements ?? '—'}건</em>
                            </h2>
                            <Link to="/compare" className={styles.compare}>
                                <Icon name="file" /> 공고 비교
                            </Link>
                            <NoticeSort
                                value={sort}
                                onChange={(value) => change('sort', value)}
                            />
                        </div>
                    </div>
                </div>
            </div>
            <section className={styles.results} aria-label="공고 검색 결과">
                <State
                    loading={query.isPending}
                    error={query.error}
                    retry={() => void query.refetch()}
                />
                <div className={styles.list} data-notice-list>
                    {query.data?.items.map((n) => (
                        <NoticeCard
                            key={n.id}
                            notice={n}
                            expanded={activePreview?.id === n.id}
                            toggle={() =>
                                setPreview((previous) =>
                                    previous?.id === n.id &&
                                    previous.search === normalized.toString()
                                        ? null
                                        : {
                                              id: n.id,
                                              search: normalized.toString(),
                                          },
                                )
                            }
                        />
                    ))}
                </div>
                {query.data?.items.length === 0 && (
                    <Card>
                        <h2>조건에 맞는 공고가 없어요</h2>
                        <Button onClick={() => setParams({})}>
                            검색 조건 초기화
                        </Button>
                        <a
                            className={cx('link-button outline-button')}
                            href="https://apply.lh.or.kr/lhapply/apply/noti/mp/list.do?mi=1331"
                            rel="noreferrer"
                            target="_blank"
                        >
                            LH 공식 공고 목록 보기
                        </a>
                    </Card>
                )}
                <p className={styles.note}>
                    <Icon name="info" />
                    접수 상태와 준비 안내 제공 여부는 별개입니다.
                </p>
                {query.data && query.data.totalPages > 0 && (
                    <nav className={styles.pagination} aria-label="공고 페이지">
                        <button
                            disabled={page === 0}
                            aria-label="이전 페이지"
                            onClick={() => goToPage(page - 1)}
                        >
                            <Icon name="back" /> 이전
                        </button>
                        <span
                            aria-current="page"
                            aria-label={`전체 ${query.data.totalPages}페이지 중 ${page + 1}페이지`}
                        >
                            <strong>{page + 1}</strong> /{' '}
                            {query.data.totalPages}페이지
                        </span>
                        <button
                            disabled={page + 1 >= query.data.totalPages}
                            aria-label="다음 페이지"
                            onClick={() => goToPage(page + 1)}
                        >
                            다음 <Icon name="arrow" />
                        </button>
                    </nav>
                )}
            </section>
        </div>
    );
}

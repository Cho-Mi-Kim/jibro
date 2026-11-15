import { animateScroll } from '../shared/ui/animateScroll';
import noticeStyles from '../features/housing/NoticeList.module.css';
import { bindClasses } from '../shared/styles/classes';
import { useSearchParams } from 'react-router';
import { useEffect, useRef } from 'react';
import { useGuides } from '../features/guide/useGuides';
import { Card, State } from '../shared/ui/ui';
import { EvidenceLink } from '../features/housing/EvidenceLink';
import { Disc } from '../shared/ui/Disc';
import { Icon, type IconName } from '../shared/ui/Icon';
import { prefersReducedMotion } from '../features/accessibility/preferences';
import guideClasses from '../features/guide/Guide.module.css';

const cx = bindClasses(guideClasses);

const categories: [string, IconName, string][] = [
    ['지원제도 알아보기', 'home', 'red'],
    ['조건과 용어', 'file', 'amber'],
    ['서류 발급 안내', 'file', 'green'],
    ['신청 절차', 'calendar', 'red'],
    ['공식 문의처', 'phone', 'amber'],
];
export function GuidePage() {
    const [params, setParams] = useSearchParams();
    const category = params.get('category');
    const keyword = params.get('q') ?? '';
    const page = Math.max(
        0,
        Math.min(10000, Math.floor(Number(params.get('page')) || 0)),
    );
    const q = useGuides(category ?? '', keyword, page);
    const layoutRef = useRef<HTMLDivElement>(null);
    useEffect(() => {
        layoutRef.current?.style.removeProperty('padding-bottom');
    }, [category, keyword, page]);
    function alignQuestion(card: HTMLDetailsElement) {
        if (card.open) return;
        requestAnimationFrame(() => {
            const layout = layoutRef.current;
            if (!layout || !card.isConnected || !card.open) return;
            const header = document.querySelector('[data-guide-header]');
            const offset = (header?.getBoundingClientRect().height ?? 0) + 8;
            const top = Math.max(
                0,
                card.getBoundingClientRect().top + window.scrollY - offset,
            );
            const previousSpace = parseFloat(layout.style.paddingBottom) || 0;
            const available =
                document.documentElement.scrollHeight -
                previousSpace -
                window.innerHeight;
            layout.style.paddingBottom = `${Math.ceil(Math.max(0, top - available))}px`;
            animateScroll(window, top, prefersReducedMotion());
        });
    }
    function change(key: string, value: string) {
        const next = new URLSearchParams(params);
        if (value) next.set(key, value);
        else next.delete(key);
        next.delete('page');
        setParams(next, { replace: true });
    }
    function goToPage(nextPage: number) {
        const next = new URLSearchParams(params);
        next.set('page', String(nextPage));
        setParams(next);
        window.scrollTo({ top: 0, behavior: 'instant' });
    }
    const selected = q.waiting ? undefined : q.data?.items;
    return (
        <>
            <header className={cx('guide-heading')}>
                <h1 className={cx('visually-hidden')}>가이드</h1>
                <p>용어와 절차를 확인하세요.</p>
            </header>
            <form
                className={noticeStyles.search}
                role="search"
                onSubmit={(event) => event.preventDefault()}
            >
                <button type="submit" aria-label="가이드 검색">
                    <Icon name="search" />
                </button>
                <input
                    name="keyword"
                    type="search"
                    aria-label="가이드 검색"
                    placeholder="용어, 서류 또는 절차 검색"
                    value={keyword}
                    onChange={(e) => change('q', e.target.value)}
                />
            </form>
            <div ref={layoutRef} className={cx('guide-layout')}>
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
                                    change(
                                        'category',
                                        category === label ? '' : label,
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
                    <h2>
                        {keyword ? '가이드 검색 결과' : '자주 확인하는 질문'}
                    </h2>
                    <State
                        loading={q.isPending || q.waiting}
                        error={q.error}
                        retry={() => void q.refetch()}
                    />
                    {selected?.map((g, i) => (
                        <details
                            className={cx('card faq')}
                            key={g.id}
                            open={i === 0}
                        >
                            <summary
                                onClick={(event) =>
                                    alignQuestion(
                                        event.currentTarget
                                            .parentElement as HTMLDetailsElement,
                                    )
                                }
                            >
                                <Disc
                                    name={i === 1 ? 'people' : 'file'}
                                    tone={i === 1 ? 'amber' : 'red'}
                                />
                                <span>{g.title}</span>
                                <Icon name="down" />
                            </summary>
                            <p>{g.body}</p>
                            <EvidenceLink
                                evidence={g.evidence}
                                checkedAtOnNewLine
                            />
                        </details>
                    ))}
                    {selected?.length === 0 && (
                        <Card>
                            <h3>검색 조건에 맞는 가이드가 없습니다.</h3>
                            <button
                                className={cx('text-button')}
                                onClick={() => setParams({})}
                            >
                                전체 가이드 보기
                            </button>
                        </Card>
                    )}
                    {(page > 0 || q.data?.hasMore) && (
                        <nav
                            className={guideClasses.pagination}
                            aria-label="가이드 페이지"
                        >
                            <button
                                disabled={
                                    page === 0 || q.waiting || q.isPending
                                }
                                onClick={() => goToPage(page - 1)}
                                aria-label="이전 페이지"
                            >
                                <Icon name="back" />
                            </button>
                            <span aria-current="page">{page + 1}</span>
                            <button
                                disabled={
                                    !q.data?.hasMore || q.waiting || q.isPending
                                }
                                onClick={() => goToPage(page + 1)}
                                aria-label="다음 페이지"
                            >
                                <Icon name="arrow" />
                            </button>
                        </nav>
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

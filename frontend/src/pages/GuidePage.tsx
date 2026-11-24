import { animateScroll } from '../shared/ui/animateScroll';
import { handleDisclosureCardClick } from '../shared/ui/handleDisclosureCardClick';
import noticeStyles from '../features/housing/NoticeList.module.css';
import { bindClasses } from '../shared/styles/classes';
import { useSearchParams } from 'react-router';
import { useEffect, useRef } from 'react';
import { useGuides } from '../features/guide/useGuides';
import { Card, State } from '../shared/ui/ui';
import { GuideAnswer } from '../features/guide/GuideAnswer';
import { Pagination } from '../shared/ui/Pagination';
import { usePageScroll } from '../shared/ui/usePageScroll';
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
    const { contentRef: questionsRef, preparePageChange } = usePageScroll({
        page,
        ready:
            !q.waiting && !q.isPending && (q.isError || q.data?.page === page),
        scope: JSON.stringify([category, keyword]),
        headerSelector: '[data-guide-header]',
        extraSpace: () =>
            parseFloat(layoutRef.current?.style.paddingBottom ?? '') || 0,
    });
    useEffect(() => {
        layoutRef.current?.style.removeProperty('padding-bottom');
    }, [category, keyword, page]);
    function alignQuestion(card: HTMLDetailsElement) {
        requestAnimationFrame(() => {
            const layout = layoutRef.current;
            if (!layout || !card.isConnected) return;
            const header = document.querySelector('[data-guide-header]');
            const offset = (header?.getBoundingClientRect().height ?? 0) + 8;
            animateScroll(
                window,
                () => {
                    if (!card.isConnected) return window.scrollY;
                    // Another answer may still be closing above this question.
                    const bounds = card.getBoundingClientRect();
                    const top = Math.max(
                        0,
                        bounds.top + window.scrollY - offset,
                    );
                    const summary = card.querySelector('summary');
                    const closingSpace =
                        !card.open && summary
                            ? Math.max(
                                  0,
                                  bounds.bottom -
                                      summary.getBoundingClientRect().bottom,
                              )
                            : 0;
                    const previousSpace =
                        parseFloat(layout.style.paddingBottom) || 0;
                    const available =
                        document.documentElement.scrollHeight -
                        previousSpace -
                        closingSpace -
                        window.innerHeight;
                    layout.style.paddingBottom = `${Math.ceil(Math.max(0, top - available))}px`;
                    return top;
                },
                prefersReducedMotion(),
            );
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
        preparePageChange(nextPage);
        const next = new URLSearchParams(params);
        next.set('page', String(nextPage));
        setParams(next);
    }
    const selected = q.waiting ? undefined : q.data?.items;
    return (
        <>
            <header className={cx('guide-heading')}>
                <h1 className={cx('visually-hidden')}>가이드</h1>
                <p>제도부터 서류와 신청 절차까지 쉽게 확인해요.</p>
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
                <section ref={questionsRef} aria-label="가이드 질문 목록">
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
                            name="guide-questions"
                            className={cx('card faq')}
                            key={JSON.stringify([
                                category ?? '',
                                keyword,
                                page,
                                g.id,
                            ])}
                            onClick={(event) => {
                                const card = event.currentTarget;
                                handleDisclosureCardClick(event, () =>
                                    alignQuestion(card),
                                );
                            }}
                        >
                            <summary>
                                <Disc
                                    name={i === 1 ? 'people' : 'file'}
                                    tone={i === 1 ? 'amber' : 'red'}
                                />
                                <span>{g.title}</span>
                                <Icon name="down" />
                            </summary>
                            <GuideAnswer guide={g} />
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
                    {q.data && (
                        <Pagination
                            page={page}
                            totalPages={q.data.totalPages}
                            label="가이드 페이지"
                            onPageChange={goToPage}
                            disabled={q.waiting || q.isPending}
                        />
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

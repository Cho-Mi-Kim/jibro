import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes, useNavigate } from 'react-router';
import { NoticesPage } from './NoticePages';
import { api } from '../features/housing/api';
import type { NoticeListItem, Page } from '../features/housing/types';
import { NoticeVisits, type Visits } from '../features/housing/NoticeListVisit';
import * as scrolling from '../shared/ui/animateScroll';

vi.mock('../features/housing/api', () => ({
    api: { notices: vi.fn(), filters: vi.fn() },
}));
vi.mock('../features/housing/NoticeCard', () => ({
    NoticeCard: ({
        notice,
        expanded,
        restoreExpanded,
        toggle,
        onOpenDetail,
    }: {
        notice: NoticeListItem;
        expanded: boolean;
        restoreExpanded?: boolean;
        toggle: () => void;
        onOpenDetail?: () => void;
    }) => (
        <article
            data-expanded={expanded}
            data-restored={restoreExpanded || undefined}
        >
            <h3>
                <button onClick={toggle}>{notice.title}</button>
            </h3>
            {expanded && (
                <Link to="/detail" onClick={onOpenDetail}>
                    공고 자세히 보기
                </Link>
            )}
        </article>
    ),
}));

const notice = (page: number): NoticeListItem => ({
    id: `page-${page}`,
    title: `페이지 ${page + 1} 공고`,
    agency: 'LH',
    region: '서울',
    type: '국민임대',
    status: 'OPEN',
    startDate: '2030-01-01',
    endDate: '2030-02-01',
    publishedDate: '2030-01-01',
    guideState: 'AVAILABLE',
    partialGuidance: false,
});
const result = (page: number): Page<NoticeListItem> => ({
    items: [notice(page)],
    page,
    size: 20,
    totalElements: 21,
    totalPages: 2,
});
function DetailReturn() {
    const navigate = useNavigate();
    return (
        <button onClick={() => void navigate(-1)}>
            공고 목록으로 돌아가기
        </button>
    );
}
function mount(
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
    path = '/notices',
) {
    const visits: Visits = { lists: new Map(), details: new Map() };
    const view = render(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={[path]}>
                <header data-notice-header />
                <NoticeVisits.Provider value={visits}>
                    <Routes>
                        <Route path="/notices" element={<NoticesPage />} />
                        <Route path="/detail" element={<DetailReturn />} />
                    </Routes>
                </NoticeVisits.Provider>
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return { ...view, client };
}

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('scrollTo', vi.fn());
    vi.stubGlobal('scrollY', 0);
    vi.stubGlobal(
        'matchMedia',
        vi.fn(() => ({ matches: false })),
    );
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
    vi.mocked(api.filters).mockResolvedValue({ regions: [], types: [] });
    vi.mocked(api.notices).mockImplementation(async (query) =>
        result(Number(new URLSearchParams(query).get('page'))),
    );
});
afterEach(() => {
    delete document.documentElement.dataset.reduceMotion;
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

it('다음 페이지가 준비될 때까지 목록 높이를 유지하고 헤더와 검색 도구 아래로 한 번 이동한다', async () => {
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockReturnValue(() => {});
    let resolvePage!: (data: Page<NoticeListItem>) => void;
    const pending = new Promise<Page<NoticeListItem>>((resolve) => {
        resolvePage = resolve;
    });
    vi.mocked(api.notices).mockImplementation((query) =>
        new URLSearchParams(query).get('page') === '1'
            ? pending
            : Promise.resolve(result(0)),
    );
    vi.stubGlobal('scrollY', 1600);
    const { container, client } = mount();
    try {
        await screen.findByRole('button', { name: '페이지 1 공고' });
        const section = screen.getByRole('region', { name: '공고 검색 결과' });
        vi.spyOn(section, 'getBoundingClientRect').mockReturnValue(
            new DOMRect(0, -1100, 350, 2400),
        );
        vi.spyOn(
            container.querySelector('[data-notice-list]')!,
            'getBoundingClientRect',
        ).mockReturnValue(new DOMRect(0, -1100, 350, 2300));
        vi.spyOn(
            container.querySelector('[data-notice-header]')!,
            'getBoundingClientRect',
        ).mockReturnValue(new DOMRect(0, 0, 390, 72));
        vi.spyOn(
            container.querySelector('[data-notice-controls]')!,
            'getBoundingClientRect',
        ).mockReturnValue(new DOMRect(0, 72, 390, 120));
        await userEvent.click(
            screen.getByRole('button', { name: '다음 페이지' }),
        );
        await waitFor(() => expect(api.notices).toHaveBeenCalledTimes(2));
        expect(parseFloat(section.style.minHeight)).toBeGreaterThanOrEqual(
            2400,
        );
        expect(scroll).not.toHaveBeenCalled();
        expect(window.scrollTo).not.toHaveBeenCalled();
        await act(async () => resolvePage(result(1)));
        await screen.findByRole('button', { name: '페이지 2 공고' });
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        expect(scroll).toHaveBeenCalledWith(
            window,
            expect.any(Function),
            false,
            expect.any(Function),
        );
        const top = scroll.mock.calls[0][1];
        expect(typeof top === 'function' ? top() : top).toBe(300);
        act(() => scroll.mock.calls[0][3]?.());
        expect(section.style.minHeight).toBe('');
    } finally {
        client.clear();
    }
});

it('상세에서 돌아오면 기존 위치를 복원하고 페이지 이동 뒤에는 그 위치가 스크롤을 덮어쓰지 않는다', async () => {
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockImplementation((_target, _top, _reduced, finish) => {
            finish?.();
            return () => {};
        });
    const { client } = mount(undefined, '/notices?page=0');
    try {
        await userEvent.click(
            await screen.findByRole('button', { name: '페이지 1 공고' }),
        );
        vi.stubGlobal('scrollY', 640);
        await userEvent.click(
            screen.getByRole('link', { name: '공고 자세히 보기' }),
        );
        await userEvent.click(
            await screen.findByRole('button', {
                name: '공고 목록으로 돌아가기',
            }),
        );
        const restored = await screen.findByRole('button', {
            name: '페이지 1 공고',
        });
        expect(restored.closest('article')).toHaveAttribute(
            'data-restored',
            'true',
        );
        expect(window.scrollTo).toHaveBeenLastCalledWith({
            top: 640,
            behavior: 'instant',
        });
        expect(scroll).not.toHaveBeenCalled();
        vi.mocked(window.scrollTo).mockClear();
        await userEvent.click(
            screen.getByRole('button', { name: '다음 페이지' }),
        );
        await screen.findByRole('button', { name: '페이지 2 공고' });
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        expect(window.scrollTo).not.toHaveBeenCalled();
        document.documentElement.dataset.reduceMotion = 'true';
        await userEvent.click(
            screen.getByRole('button', { name: '이전 페이지' }),
        );
        const previous = await screen.findByRole('button', {
            name: '페이지 1 공고',
        });
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(2));
        expect(scroll).toHaveBeenLastCalledWith(
            window,
            expect.any(Function),
            true,
            expect.any(Function),
        );
        expect(previous.closest('article')).not.toHaveAttribute(
            'data-restored',
        );
        expect(window.scrollTo).not.toHaveBeenCalled();
        expect(api.notices).toHaveBeenCalledTimes(2);
    } finally {
        client.clear();
    }
});

it('최초 조회와 공고 내용 자동 재조회는 페이지 이동 스크롤을 시작하지 않는다', async () => {
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockReturnValue(() => {});
    const { client } = mount();
    try {
        await screen.findByRole('button', { name: '페이지 1 공고' });
        expect(scroll).not.toHaveBeenCalled();
        vi.mocked(api.notices).mockResolvedValue({
            ...result(0),
            items: [{ ...notice(0), title: '새로 확인한 공고' }],
        });
        await act(async () => {
            await client.invalidateQueries({ queryKey: ['notices'] });
        });
        await screen.findByRole('button', { name: '새로 확인한 공고' });
        expect(scroll).not.toHaveBeenCalled();
        expect(window.scrollTo).not.toHaveBeenCalled();
    } finally {
        client.clear();
    }
});

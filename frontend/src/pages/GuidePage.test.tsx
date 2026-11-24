import { beforeEach, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
    focusManager,
    QueryClient,
    QueryClientProvider,
} from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { GuidePage } from './GuidePage';
import * as scrolling from '../shared/ui/animateScroll';
import { api } from '../features/housing/api';
import type { Guide, GuidePageData } from '../features/housing/types';

vi.mock('../features/housing/api', () => ({ api: { guides: vi.fn() } }));
const guide = (id: number): Guide => ({
    id: String(id),
    category: '조건과 용어',
    title: `안내 ${id}`,
    body: '확인한 내용',
    evidence: {
        label: '공고 원문',
        url: 'https://apply.lh.or.kr/',
        checkedAt: '2030-01-01',
    },
});
beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('scrollTo', vi.fn());
    vi.mocked(api.guides).mockImplementation(async (query) => {
        const params = new URLSearchParams(query);
        const page = Number(params.get('page'));
        return {
            items: Array.from({ length: 20 }, (_, i) => guide(page * 20 + i)),
            page,
            hasMore: page === 0,
            totalPages: 2,
        };
    });
});
function mount(
    path = '/guide',
    client = new QueryClient({
        defaultOptions: {
            queries: { retry: false, refetchOnWindowFocus: false },
        },
    }),
) {
    return render(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={[path]}>
                <GuidePage />
            </MemoryRouter>
        </QueryClientProvider>,
    );
}

// jsdom은 details name 그룹을 지원하지 않아 단일 펼침은 실제 브라우저에서 확인한다.
it('질문 카드의 본문과 여백으로 답변을 여닫고 출처 링크와 질문 제목은 각각 동작한다', async () => {
    const user = userEvent.setup();
    const { container } = mount();
    const first = (await screen.findByText('안내 0')).closest('details')!;
    const second = screen.getByText('안내 1').closest('details')!;
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
    await user.click(within(first).getByText('안내 0'));
    expect(first.open).toBe(true);
    await user.click(within(first).getByText('안내 0'));
    expect(first.open).toBe(false);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
    await user.click(second);
    expect(second.open).toBe(true);
    expect(container.querySelectorAll('details[open]')).toHaveLength(1);
    await user.click(within(second).getByRole('link'));
    expect(second.open).toBe(true);
    expect(container.querySelectorAll('details[open]')).toHaveLength(1);
    await user.click(within(second).getByText('확인한 내용'));
    expect(second.open).toBe(false);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
    await user.click(within(second).getByText('안내 1'));
    expect(second.open).toBe(true);
    expect(container.querySelectorAll('details[open]')).toHaveLength(1);
    await user.click(second);
    expect(second.open).toBe(false);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
});

it('자동으로 새 내용을 읽어도 문장별 줄바꿈과 카드 펼침 상태를 유지한다', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({
        defaultOptions: {
            queries: { retry: false, refetchOnWindowFocus: false },
        },
    });
    const firstPage = {
        items: [guide(0), guide(1)],
        page: 0,
        hasMore: true,
        totalPages: 2,
    };
    vi.mocked(api.guides)
        .mockResolvedValueOnce(firstPage)
        .mockResolvedValue({
            ...firstPage,
            items: [
                {
                    ...guide(0),
                    body: '2026. 11. 21.에 새로 확인한 안내예요. 09:00부터 발급할 수 있어요.\n\n- 1.5배 기준을 확인해요. 예외 대상은 증빙도 준비해요.',
                    scope: '새로운 공식 안내를 반영해요. 제출 기준은 해당 공고를 따라요.',
                    evidence: {
                        label: '갱신된 공식 안내',
                        url: 'https://www.gov.kr/portal/main',
                        checkedAt: '2026-11-21',
                    },
                },
                guide(1),
            ],
        });
    try {
        mount('/guide', client);
        const first = (await screen.findByText('안내 0')).closest('details')!;
        const second = screen.getByText('안내 1').closest('details')!;
        await user.click(within(second).getByText('안내 1'));
        expect(first.open).toBe(false);
        expect(second.open).toBe(true);
        await act(async () => {
            client.setQueryData(['guides', '', '', 0], firstPage, {
                updatedAt: Date.now() - 61_000,
            });
            focusManager.setFocused(false);
            focusManager.setFocused(true);
        });
        const body = await within(first).findByText(
            '2026. 11. 21.에 새로 확인한 안내예요. 09:00부터 발급할 수 있어요.',
        );
        expect(body.textContent).toBe(
            '2026. 11. 21.에 새로 확인한 안내예요.\n09:00부터 발급할 수 있어요.',
        );
        expect(first.querySelector('li')?.textContent).toBe(
            '1.5배 기준을 확인해요.\n예외 대상은 증빙도 준비해요.',
        );
        expect(
            within(first).getByText('적용 범위').parentElement?.textContent,
        ).toBe(
            '적용 범위새로운 공식 안내를 반영해요.\n제출 기준은 해당 공고를 따라요.',
        );
        expect(first.querySelector('a')).toHaveAttribute(
            'href',
            'https://www.gov.kr/portal/main',
        );
        expect(within(first).getByText('확인 2026-11-21')).toBeInTheDocument();
        expect(first.open).toBe(false);
        expect(second.open).toBe(true);
        expect(api.guides).toHaveBeenCalledTimes(2);
        expect(api.guides).toHaveBeenLastCalledWith(
            new URLSearchParams({
                category: '',
                keyword: '',
                page: '0',
            }).toString(),
        );
    } finally {
        focusManager.setFocused(undefined);
        client.clear();
    }
});

it('열려 있던 답변을 접을 때도 해당 질문을 헤더 아래로 정렬한다', async () => {
    const user = userEvent.setup();
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockReturnValue(() => {});
    try {
        mount();
        const card = (await screen.findByText('안내 0')).closest('details')!;
        await user.click(within(card).getByText('안내 0'));
        expect(card.open).toBe(true);
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        scroll.mockClear();
        vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(
            new DOMRect(0, 300, 350, 80),
        );
        await user.click(within(card).getByText('확인한 내용'));
        expect(card.open).toBe(false);
        await waitFor(() =>
            expect(scroll).toHaveBeenCalledWith(
                window,
                expect.any(Function),
                expect.any(Boolean),
            ),
        );
        const target = scroll.mock.calls[0][1];
        expect(typeof target === 'function' ? target() : target).toBe(292);
    } finally {
        scroll.mockRestore();
    }
});

it('현재 페이지의 질문만 표시하고 다음·이전 페이지의 답변을 모두 접어서 보여준다', async () => {
    const user = userEvent.setup();
    const { container } = mount();
    await screen.findByText('안내 0');
    expect(container.querySelectorAll('details')).toHaveLength(20);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
    await user.click(screen.getByText('안내 0'));
    expect(screen.getByText('안내 0').closest('details')!.open).toBe(true);
    expect(screen.getByLabelText('전체 2페이지 중 1페이지')).toHaveTextContent(
        '1 / 2페이지',
    );
    expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '다음 페이지' }));
    await screen.findByText('안내 20');
    expect(screen.getByLabelText('전체 2페이지 중 2페이지')).toHaveTextContent(
        '2 / 2페이지',
    );
    expect(screen.queryByText('안내 0')).not.toBeInTheDocument();
    expect(container.querySelectorAll('details')).toHaveLength(20);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
    await user.click(screen.getByText('안내 20'));
    expect(screen.getByText('안내 20').closest('details')!.open).toBe(true);
    expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '이전 페이지' }));
    await screen.findByText('안내 0');
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
});

it('다음 질문을 기다리는 동안 높이를 유지하고 준비된 목록 상단으로 한 번만 이동한다', async () => {
    const user = userEvent.setup();
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockReturnValue(() => {});
    let resolveNext!: (value: GuidePageData) => void;
    const nextPage = new Promise<GuidePageData>((resolve) => {
        resolveNext = resolve;
    });
    vi.mocked(api.guides).mockImplementation((query) =>
        new URLSearchParams(query).get('page') === '1'
            ? nextPage
            : Promise.resolve({
                  items: [guide(0)],
                  page: 0,
                  hasMore: true,
                  totalPages: 2,
              }),
    );
    const header = document.createElement('header');
    header.dataset.guideHeader = '';
    document.body.append(header);
    vi.spyOn(header, 'getBoundingClientRect').mockReturnValue(
        new DOMRect(0, 0, 390, 72),
    );
    vi.stubGlobal('scrollY', 1600);
    try {
        mount();
        await screen.findByText('안내 0');
        const list = screen.getByRole('region', { name: '가이드 질문 목록' });
        vi.spyOn(list, 'getBoundingClientRect').mockReturnValue(
            new DOMRect(0, -1100, 350, 2400),
        );
        vi.spyOn(
            within(list).getByRole('heading', { name: '자주 확인하는 질문' }),
            'getBoundingClientRect',
        ).mockReturnValue(new DOMRect(0, -1100, 350, 32));
        expect(scroll).not.toHaveBeenCalled();

        await user.click(screen.getByRole('button', { name: '다음 페이지' }));
        await waitFor(() => expect(api.guides).toHaveBeenCalledTimes(2));
        expect(parseFloat(list.style.minHeight)).toBeGreaterThanOrEqual(2400);
        expect(scroll).not.toHaveBeenCalled();
        expect(window.scrollTo).not.toHaveBeenCalled();

        await act(async () =>
            resolveNext({
                items: [guide(20)],
                page: 1,
                hasMore: false,
                totalPages: 2,
            }),
        );
        await screen.findByText('안내 20');
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        expect(scroll).toHaveBeenCalledWith(
            window,
            expect.any(Function),
            false,
            expect.any(Function),
        );
        const target = scroll.mock.calls[0][1];
        expect(typeof target === 'function' ? target() : target).toBe(420);
        expect(parseFloat(list.style.minHeight)).toBeGreaterThanOrEqual(2400);
        act(() => scroll.mock.calls[0][3]?.());
        expect(list.style.minHeight).toBe('');
    } finally {
        scroll.mockRestore();
        header.remove();
    }
});

it('동작 줄이기를 존중하며 캐시된 이전 페이지로 돌아올 때도 목록 상단으로 이동한다', async () => {
    const user = userEvent.setup();
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockImplementation((_target, _top, _reduced, finish) => {
            finish?.();
            return () => {};
        });
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    client.setQueryData(['guides', '', '', 0], {
        items: [guide(0)],
        page: 0,
        hasMore: true,
        totalPages: 2,
    });
    client.setQueryData(['guides', '', '', 1], {
        items: [guide(20)],
        page: 1,
        hasMore: false,
        totalPages: 2,
    });
    document.documentElement.dataset.reduceMotion = 'true';
    try {
        mount('/guide?page=1', client);
        await screen.findByText('안내 20');
        expect(scroll).not.toHaveBeenCalled();
        await user.click(screen.getByRole('button', { name: '이전 페이지' }));
        await screen.findByText('안내 0');
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        expect(scroll).toHaveBeenCalledWith(
            window,
            expect.any(Function),
            true,
            expect.any(Function),
        );
        expect(
            screen.getByRole('region', { name: '가이드 질문 목록' }).style
                .minHeight,
        ).toBe('');
        expect(api.guides).not.toHaveBeenCalled();
    } finally {
        delete document.documentElement.dataset.reduceMotion;
        scroll.mockRestore();
        client.clear();
    }
});

it('페이지를 누르지 않은 초기 조회와 자동 재조회는 스크롤 위치를 바꾸지 않는다', async () => {
    const scroll = vi
        .spyOn(scrolling, 'animateScroll')
        .mockReturnValue(() => {});
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    try {
        mount('/guide', client);
        await screen.findByText('안내 0');
        expect(scroll).not.toHaveBeenCalled();
        vi.mocked(api.guides).mockResolvedValue({
            items: [{ ...guide(0), body: '자동으로 갱신된 안내' }],
            page: 0,
            hasMore: false,
            totalPages: 1,
        });
        await act(async () => {
            await client.invalidateQueries({ queryKey: ['guides', '', '', 0] });
        });
        await screen.findByText('자동으로 갱신된 안내');
        expect(scroll).not.toHaveBeenCalled();
        expect(window.scrollTo).not.toHaveBeenCalled();
    } finally {
        scroll.mockRestore();
        client.clear();
    }
});

it('같은 질문이 캐시에 있어도 주제를 바꾸거나 선택을 해제하면 모든 답변을 접는다', async () => {
    const user = userEvent.setup();
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false } },
    });
    const firstPage = {
        items: [guide(0), guide(1)],
        page: 0,
        hasMore: false,
        totalPages: 1,
    };
    for (const category of ['', '조건과 용어', '서류 발급 안내']) {
        client.setQueryData(['guides', category, '', 0], firstPage);
    }
    try {
        const { container } = mount('/guide', client);
        await screen.findByText('안내 0');
        for (const topic of [
            '조건과 용어',
            '서류 발급 안내',
            '서류 발급 안내',
        ]) {
            await user.click(screen.getByText('안내 1'));
            expect(container.querySelectorAll('details[open]')).toHaveLength(1);
            await user.click(screen.getByRole('button', { name: topic }));
            expect(container.querySelectorAll('details')).toHaveLength(2);
            expect(container.querySelectorAll('details[open]')).toHaveLength(0);
        }
        expect(
            screen.getByRole('button', { name: '서류 발급 안내' }),
        ).toHaveAttribute('aria-pressed', 'false');
        expect(api.guides).not.toHaveBeenCalled();
    } finally {
        client.clear();
    }
});

it('서버 검색 시 페이지를 초기화하고 주제를 해제하며 검색 결과의 모든 답변을 접는다', async () => {
    const user = userEvent.setup();
    const { container } = mount('/guide?page=2');
    await screen.findByText('안내 40');
    await user.click(screen.getByRole('button', { name: '조건과 용어' }));
    await waitFor(() =>
        expect(api.guides).toHaveBeenLastCalledWith(
            new URLSearchParams({
                category: '조건과 용어',
                keyword: '',
                page: '0',
            }).toString(),
        ),
    );
    await user.click(screen.getByRole('button', { name: '조건과 용어' }));
    await screen.findByText('안내 0');
    await user.click(screen.getByText('안내 0'));
    expect(screen.getByText('안내 0').closest('details')!.open).toBe(true);
    await user.type(screen.getByRole('searchbox'), '주민등록');
    await waitFor(() =>
        expect(api.guides).toHaveBeenLastCalledWith(
            new URLSearchParams({
                category: '',
                keyword: '주민등록',
                page: '0',
            }).toString(),
        ),
    );
    await screen.findByText('안내 0');
    expect(container.querySelectorAll('details')).toHaveLength(20);
    expect(container.querySelectorAll('details[open]')).toHaveLength(0);
});

it('검토한 답변의 문단·목록·적용 범위와 모든 공식 출처를 보여준다', async () => {
    const user = userEvent.setup();
    vi.mocked(api.guides).mockResolvedValue({
        items: [
            {
                ...guide(0),
                body: '먼저 서류 종류를 확인해요.\n\n- 등본은 세대를 확인해요.\n- 초본은 주소 이력을 확인해요.\n\n1. 공고를 확인해요.\n2. 필요한 서류를 발급해요.',
                scope: '발급일과 표시 항목은 해당 공고를 따라요.',
                additionalEvidence: [
                    {
                        label: '정부24 주민등록표 등본·초본',
                        url: 'https://www.gov.kr/',
                        checkedAt: '2026-11-21',
                    },
                ],
            },
        ],
        page: 0,
        hasMore: false,
        totalPages: 1,
    });
    mount();
    const card = (await screen.findByText('안내 0')).closest('details')!;
    await user.click(within(card).getByText('안내 0'));
    expect(within(card).getAllByRole('list')).toHaveLength(2);
    expect(within(card).getAllByRole('listitem')).toHaveLength(4);
    expect(within(card).getByText('적용 범위')).toBeVisible();
    expect(
        within(card).getByText('발급일과 표시 항목은 해당 공고를 따라요.'),
    ).toBeVisible();
    expect(within(card).getAllByRole('link')).toHaveLength(2);
    expect(within(card).getByText('확인 2026-11-21')).toBeVisible();
});

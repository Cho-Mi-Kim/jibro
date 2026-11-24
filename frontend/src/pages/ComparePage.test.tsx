import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import {
    MemoryRouter,
    Route,
    Routes,
    useLocation,
    useNavigate,
} from 'react-router';
import { api } from '../features/housing/api';
import { memberApi } from '../features/member/memberApi';
import { ComparePage, VersionsPage } from './ComparePage';
import { NoticeListLayout } from '../features/housing/NoticeListLayout';
import { comparisonApi } from '../features/comparison/comparisonApi';
import { comparisonFixture } from '../features/comparison/comparisonFixtures';

const data = ['A', 'B', 'C'].map((id) => {
    const item = comparisonFixture(id);
    item.notice.title = `2026년 ${id} 청년 공고 (2026.10.27)`;
    return item;
});
function NavigationProbe() {
    const location = useLocation();
    const navigate = useNavigate();
    return (
        <>
            <output aria-label="현재 주소">
                {location.pathname + location.search}
            </output>
            <button onClick={() => navigate(-1)}>브라우저 뒤로</button>
        </>
    );
}
function mount(path = '/compare?a=A&b=B') {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    render(
        <QueryClientProvider client={client}>
            <MemoryRouter initialEntries={[path]}>
                <NavigationProbe />
                <Routes>
                    <Route element={<NoticeListLayout />}>
                        <Route path="/compare" element={<ComparePage />} />
                        <Route
                            path="/notices/:noticeId/versions"
                            element={<VersionsPage />}
                        />
                    </Route>
                </Routes>
            </MemoryRouter>
        </QueryClientProvider>,
    );
    return client;
}
function selection(number: number) {
    return within(
        screen.getByRole('region', { name: `${number}번 비교 공고` }),
    );
}
beforeEach(() => {
    vi.spyOn(comparisonApi, 'notice').mockImplementation(async (id) =>
        data.find((item) => item.notice.id === id)!,
    );
    vi.spyOn(api, 'notices').mockResolvedValue({
        items: data.map((item) => ({ ...item.notice, partialGuidance: false })),
        page: 0,
        size: 10,
        totalElements: 3,
        totalPages: 1,
    });
});
afterEach(() => vi.restoreAllMocks());

describe('공고 비교 선택 흐름', () => {
    it('주소의 두 공고를 읽고 한 공고 변경 후 뒤로 가면 원래 비교를 복원한다', async () => {
        const user = userEvent.setup();
        mount();
        expect(await selection(1).findByText('A 청년')).toBeVisible();
        expect(await selection(2).findByText('B 청년')).toBeVisible();
        await user.click(
            selection(2).getByRole('button', { name: '2번 공고 변경' }),
        );
        await user.click(await screen.findByRole('button', { name: /C 청년/ }));
        expect(await selection(2).findByText('C 청년')).toBeVisible();
        const basic = within(screen.getByRole('region', { name: '기본 정보' }));
        expect(
            basic.getByRole('heading', { name: /C 청년/ }),
        ).toHaveTextContent(/^2\s*C 청년$/);
        expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
            '/compare?a=A&b=C',
        );
        await user.click(screen.getByRole('button', { name: '브라우저 뒤로' }));
        expect(await selection(2).findByText('B 청년')).toBeVisible();
        expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
            '/compare?a=A&b=B',
        );
        expect(
            screen.queryByRole('heading', { name: 'C 거주지 기준' }),
        ).not.toBeInTheDocument();
    });

    it('첫 번째를 해제하고 다시 골라도 두 번째 선택은 유지하고 중복 후보는 제외한다', async () => {
        const user = userEvent.setup();
        mount();
        await selection(1).findByText('A 청년');
        await user.click(
            selection(1).getByRole('button', { name: '1번 공고 선택 해제' }),
        );
        const search = screen.getByRole('region', { name: '1번 공고 검색' });
        expect(
            await within(search).findByRole('button', { name: /A 청년/ }),
        ).toBeVisible();
        expect(
            within(search).queryByRole('button', { name: /B 청년/ }),
        ).not.toBeInTheDocument();
        await user.click(
            within(search).getByRole('button', { name: /C 청년/ }),
        );
        expect(await selection(1).findByText('C 청년')).toBeVisible();
        expect(selection(2).getByText('B 청년')).toBeVisible();
        expect(screen.getByLabelText('현재 주소')).toHaveTextContent('b=B&a=C');
    });

    it('불러오기 오류를 숨기지 않고 한 칸만 재시도할 수 있다', async () => {
        vi.mocked(comparisonApi.notice).mockRejectedValueOnce(
            new Error('통신 오류'),
        );
        const user = userEvent.setup();
        mount('/compare?a=A');
        expect(await selection(1).findByRole('alert')).toBeVisible();
        expect(
            screen.queryByRole('region', { name: '공식 일정' }),
        ).not.toBeInTheDocument();
        await user.click(
            selection(1).getByRole('button', { name: '다시 시도' }),
        );
        expect(await selection(1).findByText('A 청년')).toBeVisible();
        expect(screen.getByRole('region', { name: '공식 일정' })).toBeVisible();
    });

    it('선택 전 검색과 빈 검색결과·로딩 상태를 표시한다', async () => {
        vi.mocked(api.notices).mockResolvedValue({
            items: [],
            page: 0,
            size: 10,
            totalElements: 0,
            totalPages: 0,
        });
        mount('/compare');
        expect(
            within(
                screen.getByRole('region', { name: '1번 공고 검색' }),
            ).getByRole('status'),
        ).toHaveTextContent('불러오고');
        expect(await screen.findByText(/선택할 공고가 없습니다/)).toBeVisible();
        expect(screen.getByRole('button', { name: '다음' })).toBeDisabled();
        expect(
            screen.queryByRole('region', { name: '공식 일정' }),
        ).not.toBeInTheDocument();
    });

    it('공고 변경 검색은 새 검색어로 조회하고 기존 두 공고를 유지한 채 취소한다', async () => {
        const user = userEvent.setup();
        mount();
        await selection(1).findByText('A 청년');
        await user.click(
            selection(1).getByRole('button', { name: '1번 공고 변경' }),
        );
        await user.type(
            screen.getByRole('textbox', { name: '1번 공고 검색' }),
            'LH',
        );
        await waitFor(() =>
            expect(api.notices).toHaveBeenLastCalledWith(
                'keyword=LH&page=0&size=10',
            ),
        );
        await user.click(screen.getByRole('button', { name: '변경 취소' }));
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
        expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
            '/compare?a=A&b=B',
        );
    });
});

describe('기존 수정 전후 비교 유지', () => {
    it('선택한 이전 공개본과 현재 공개본에서 달라진 항목만 보여준다', async () => {
        vi.spyOn(memberApi, 'editions').mockResolvedValue([
            {
                version: 3,
                revision: 5,
                facts: {
                    title: '현재 공고',
                    region: '서울',
                    documents: ['새 서류'],
                },
            },
            {
                version: 2,
                revision: 4,
                facts: {
                    title: '현재 공고',
                    region: '서울',
                    documents: ['이전 서류'],
                },
            },
            {
                version: 1,
                revision: 1,
                facts: {
                    title: '최초 공고',
                    region: '서울',
                    documents: ['최초 서류'],
                },
            },
        ]);
        mount('/notices/A/versions');
        expect(await screen.findByText('이전 서류')).toBeVisible();
        expect(screen.getByText('새 서류')).toBeVisible();
        expect(
            screen.queryByRole('heading', { name: '지역' }),
        ).not.toBeInTheDocument();
        await userEvent.selectOptions(
            screen.getByRole('combobox', { name: '이전 공개본' }),
            '1.1',
        );
        expect(screen.getByText('최초 공고')).toBeVisible();
        expect(screen.getByText('최초 서류')).toBeVisible();
        expect(memberApi.editions).toHaveBeenCalledWith('A');
        expect(comparisonApi.notice).not.toHaveBeenCalled();
    });
});

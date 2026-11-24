import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Routes, Route } from 'react-router';
import { NoticeListLayout } from '../housing/NoticeListLayout';
import { Session } from '../auth/useSession';
import type { Preparation, User } from '../housing/types';
import { api } from '../housing/api';
import { comparisonApi } from './comparisonApi';
import { comparisonFixture } from './comparisonFixtures';
import { NoticeComparisonPage } from './NoticeComparisonPage';

const account: User = { id: 'owner', name: '테스트', email: '', role: 'USER' };
function fixture(id: string) {
    const data = comparisonFixture(id);
    data.notice.questions[0].options!.push({
        value: 'NOT_MY_TYPE',
        label: '해당 없음',
    });
    data.notice.questions.push({
        id: 'method',
        title: `${id} 신청 방법`,
        reason: '접수 방법을 선택하세요.',
        evidence: data.notice.evidence,
        options: [
            { value: 'ONLINE', label: '온라인 신청' },
            { value: 'VISIT', label: '현장 신청' },
        ],
    });
    data.notice.schedules!.push(
        ...[
            [
                'online-start',
                '접수 회차 · 온라인 접수 시작',
                '2026-11-10T10:00:00',
            ],
            [
                'online-end',
                '접수 회차 · 온라인 접수 마감',
                '2026-11-20T16:00:00',
            ],
            [
                'visit-start',
                '접수 회차 · 현장 접수 시작',
                '2026-12-10T10:00:00',
            ],
            ['visit-end', '접수 회차 · 현장 접수 마감', '2026-12-31T18:00:00'],
        ].map(([id, title, date]) => ({
            id,
            title,
            date,
            evidence: data.notice.evidence,
        })),
    );
    const p: Preparation = {
        id: 'p-' + id,
        noticeId: id,
        title: data.notice.title,
        version: 3,
        revision: 2,
        guideState: 'AVAILABLE',
        questions: data.notice.questions,
        answers: { residence: 'NOT_MY_TYPE', method: 'ONLINE' },
        documents: data.documentRequirements!.map((doc) => ({
            ...doc,
            necessity: doc.id === 'conditional' ? 'NOT_APPLICABLE' : 'REQUIRED',
            state: 'UNCHECKED',
            note: '',
        })),
        documentConditions: { conditional: 'residence' },
        schedules: data.notice.schedules!.map((event) => ({
            ...event,
            recorded: false,
        })),
        progress: 0,
        houseStage: 0,
        warnings: [],
        nextAction: { label: '', reason: '', path: '' },
    };
    return { data, p };
}
function mount() {
    const client = new QueryClient({
        defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    const view = (user: User | null) => (
        <QueryClientProvider client={client}>
            <Session.Provider
                value={{
                    user,
                    loading: false,
                    error: null,
                    refresh: async () => {},
                }}
            >
                <MemoryRouter initialEntries={['/compare?a=A&b=B']}>
                    <Routes>
                        <Route element={<NoticeListLayout />}>
                            <Route
                                path="/compare"
                                element={<NoticeComparisonPage />}
                            />
                        </Route>
                    </Routes>
                </MemoryRouter>
            </Session.Provider>
        </QueryClientProvider>
    );
    const result = render(view(account));
    return {
        client,
        session: (user: User | null) => result.rerender(view(user)),
    };
}
function setup(patch?: (p: Preparation) => void) {
    const a = fixture('A'),
        b = fixture('B');
    patch?.(a.p);
    vi.spyOn(comparisonApi, 'notice').mockImplementation(async (id) =>
        id === 'A' ? a.data : b.data,
    );
    vi.spyOn(api, 'notices').mockResolvedValue({
        items: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
    });
    vi.spyOn(api, 'preparations').mockResolvedValue([a.p, fixture('C').p]);
    vi.spyOn(api, 'preparation').mockResolvedValue(a.p);
    return { a, b };
}
afterEach(() => vi.restoreAllMocks());

describe('저장한 답변으로 공고별 비교 범위 선택', () => {
    it('답변한 공고만 조건·서류·접수 회차를 줄이고 미답변 공고와 공개 캐시는 유지한다', async () => {
        const { a } = setup();
        const original = structuredClone(a.data);
        const { client } = mount();
        await screen.findByText(
            '내 답변에 맞는 안내 · 공통·확인 필요 항목 포함',
        );
        const conditions = within(screen.getByRole('region', { name: '조건' }));
        expect(conditions.queryByText('A 거주지 기준')).not.toBeInTheDocument();
        expect(conditions.getByText('B 거주지 기준')).toBeInTheDocument();
        const documents = within(
            screen.getByRole('region', { name: '제출서류' }),
        );
        expect(documents.getByText('A 주민등록등본')).toBeInTheDocument();
        expect(documents.queryByText('A 추가 서류')).not.toBeInTheDocument();
        expect(documents.getByText('B 추가 서류')).toBeInTheDocument();
        const schedules = screen.getByRole('region', { name: '공식 일정' });
        await userEvent.click(
            within(schedules).getAllByText('세부 일정·공식 근거 보기')[0],
        );
        const first = within(schedules).getAllByRole('heading', { level: 3 })[0]
            .parentElement!;
        expect(
            within(first).queryByText('접수 회차 · 현장 접수 마감'),
        ).not.toBeInTheDocument();
        expect(
            within(first).getByText('접수 회차 · 온라인 접수 마감'),
        ).toBeVisible();
        expect(first).toHaveTextContent('2026.11.20');
        expect(first).not.toHaveTextContent('2026.12.31');
        expect(client.getQueryData(['notice-comparison', 'A'])).toEqual(
            original,
        );
        expect(api.preparation).toHaveBeenCalledWith('p-A');
        expect(api.preparation).not.toHaveBeenCalledWith('p-C');
    });

    it('공고 교체·로그아웃·다른 계정에서는 이전 답변을 가져오지 않는다', async () => {
        setup();
        const { session } = mount();
        await screen.findByText(/내 답변에 맞는 안내/);
        session(null);
        await waitFor(() =>
            expect(
                screen.queryByText(/내 답변에 맞는 안내/),
            ).not.toBeInTheDocument(),
        );
        expect(
            within(screen.getByRole('region', { name: '조건' })).getByText(
                'A 거주지 기준',
            ),
        ).toBeInTheDocument();
        vi.mocked(api.preparations).mockResolvedValue([]);
        session({ ...account, id: 'other' });
        await waitFor(() =>
            expect(
                screen.queryByText(/저장된 답변을 확인/),
            ).not.toBeInTheDocument(),
        );
        expect(
            screen.queryByText(/내 답변에 맞는 안내/),
        ).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '1번 공고 선택 해제' }),
        );
        expect(screen.queryByText('A 주민등록등본')).not.toBeInTheDocument();
        expect(screen.getByText('B 추가 서류')).toBeInTheDocument();
    });

    it('두 공고에 답했어도 각각의 서류 필요도와 신청 방법을 적용한다', async () => {
        const { a, b } = setup();
        b.p.answers = { residence: 'OTHER', method: 'VISIT' };
        b.p.documents[1].necessity = 'REQUIRED';
        vi.mocked(api.preparations).mockResolvedValue([a.p, b.p]);
        vi.mocked(api.preparation).mockImplementation(async (id) =>
            id === a.p.id ? a.p : b.p,
        );
        mount();
        await waitFor(() =>
            expect(screen.getAllByText(/내 답변에 맞는 안내/)).toHaveLength(2),
        );
        expect(screen.queryByText('A 추가 서류')).not.toBeInTheDocument();
        expect(screen.getByText('B 추가 서류')).toBeInTheDocument();
        const schedules = screen.getByRole('region', { name: '공식 일정' });
        await userEvent.click(
            within(schedules).getAllByText('세부 일정·공식 근거 보기')[1],
        );
        const second = within(schedules).getAllByRole('heading', {
            level: 3,
        })[1].parentElement!;
        expect(
            within(second).getByText('접수 회차 · 현장 접수 마감'),
        ).toBeVisible();
        expect(
            within(second).queryByText('접수 회차 · 온라인 접수 마감'),
        ).not.toBeInTheDocument();
        expect(second).toHaveTextContent('2026.12.31');
        expect(second).not.toHaveTextContent('2026.11.20');
    });

    it('새 안내와 버전이 다른 답변 및 저장 답변이 없는 기록은 전체 안내를 보여준다', async () => {
        const { a } = setup((p) => {
            p.version = 2;
        });
        const { client } = mount();
        await screen.findByText(/안내가 바뀌어 전체 내용을 표시/);
        expect(api.preparation).not.toHaveBeenCalled();
        expect(screen.getByText('A 추가 서류')).toBeInTheDocument();
        a.p = { ...a.p, version: 3, answers: {} };
        vi.mocked(api.preparation).mockResolvedValue(a.p);
        await act(async () => {
            client.setQueryData(['preparations', 'owner'], [a.p]);
        });
        await waitFor(() =>
            expect(api.preparation).toHaveBeenCalledWith('p-A'),
        );
        await waitFor(() =>
            expect(
                screen.queryByText(/저장된 답변을 확인/),
            ).not.toBeInTheDocument(),
        );
        expect(
            screen.queryByText(/내 답변에 맞는 안내/),
        ).not.toBeInTheDocument();
        expect(screen.getByText('A 추가 서류')).toBeInTheDocument();
    });

    it('답변 조회 실패를 알리고 전체 안내를 유지하며 재시도 성공 시 적용한다', async () => {
        const { a } = setup();
        vi.mocked(api.preparation).mockRejectedValueOnce(
            new Error('조회 실패'),
        );
        mount();
        await screen.findByText(/저장된 답변을 불러오지 못해/);
        expect(screen.getByText('A 추가 서류')).toBeInTheDocument();
        vi.mocked(api.preparation).mockResolvedValue(a.p);
        await userEvent.click(
            screen.getByRole('button', { name: '답변 다시 불러오기' }),
        );
        await screen.findByText(/내 답변에 맞는 안내/);
        expect(screen.queryByText('A 추가 서류')).not.toBeInTheDocument();
    });
});

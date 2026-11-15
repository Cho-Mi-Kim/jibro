import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Session } from '../../auth/useSession';
import { PreparationHistoryPage } from './PreparationHistoryPage';
import {
    preparationHistoryApi,
    type PreparationHistoryDetail,
} from './historyApi';

const evidence = {
    label: '당시 공고 4쪽',
    url: 'https://example.com/old-notice',
    checkedAt: '2026-10-01',
};
function entry(version: number): PreparationHistoryDetail {
    return {
        version,
        savedAt: `2026-10-0${version}T03:00:00Z`,
        preparation: {
            id: 'preparation-a',
            noticeId: 'notice-a',
            title: '저장한 준비',
            version,
            revision: 9,
            guideState: 'AVAILABLE',
            progress: 65,
            houseStage: 3,
            warnings: [],
            nextAction: {
                label: '현재에는 쓰지 않는 행동',
                reason: '',
                path: '/old-action',
            },
            questions: [
                {
                    id: 'residence',
                    title: `${version}차 거주 기준을 확인했나요?`,
                    reason: `거주 기간은 ${version}년 이상입니다. 주민등록상 주소로 확인합니다. 예외는 당시 안내를 따릅니다.`,
                    evidence,
                    options: [
                        { value: 'LOCAL', label: `${version}차 거주 선택지` },
                        { value: 'OTHER', label: '다른 지역' },
                    ],
                },
            ],
            answers: { residence: 'LOCAL' },
            documents: [
                {
                    id: 'document',
                    title: '당시 서류',
                    necessity: 'NOT_APPLICABLE',
                    state: 'READY',
                    method: '당시 발급 안내',
                    issuer: '당시 기관',
                    issueUrl: null,
                    note: `${version}차 메모`,
                    evidence,
                },
            ],
            schedules: [
                {
                    id: 'apply',
                    title: '당시 신청 일정',
                    date: '2026-10-10',
                    recorded: true,
                    evidence,
                },
            ],
        },
    };
}
beforeEach(() => {
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
    vi.spyOn(preparationHistoryApi, 'list').mockResolvedValue({
        preparationId: 'preparation-a',
        title: '저장한 준비',
        archived: false,
        entries: [entry(2), entry(1)].map(({ version, savedAt }) => ({
            version,
            savedAt,
        })),
    });
    vi.spyOn(preparationHistoryApi, 'get').mockImplementation(
        async (_, version) => entry(version),
    );
});
afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

function mount(query = '') {
    return render(
        <QueryClientProvider
            client={
                new QueryClient({
                    defaultOptions: { queries: { retry: false, gcTime: 0 } },
                })
            }
        >
            <Session.Provider
                value={{
                    user: {
                        id: 'owner',
                        name: '방문자',
                        email: '',
                        role: 'USER',
                    },
                    loading: false,
                    error: null,
                    refresh: async () => {},
                }}
            >
                <MemoryRouter
                    initialEntries={[
                        `/preparations/preparation-a/history${query}`,
                    ]}
                >
                    <Routes>
                        <Route
                            path="/preparations/:preparationId/history"
                            element={<PreparationHistoryPage />}
                        />
                    </Routes>
                </MemoryRouter>
            </Session.Provider>
        </QueryClientProvider>,
    );
}

it('최신 저장 버전부터 원래 답변·선택지·해당 없던 서류·메모·일정 기록을 읽기 전용으로 보여 준다', async () => {
    const user = userEvent.setup();
    mount();
    const record = await screen.findByRole('article', {
        name: '안내 v2 저장 기록',
    });
    expect(within(record).getByText('2차 거주 선택지')).toBeVisible();
    expect(
        within(record).getByText('준비 완료 · 당시 해당 없음'),
    ).toBeVisible();
    expect(within(record).getByText('2차 메모')).toBeVisible();
    expect(within(record).getByText('완료로 기록함')).toBeVisible();
    expect(within(record).getByText('2026-10-10')).toBeVisible();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(
        screen.queryByRole('link', { name: '현재에는 쓰지 않는 행동' }),
    ).not.toBeInTheDocument();
    expect(
        within(record).getByRole('heading', { name: '2차 거주 기준' }),
    ).toBeVisible();
    expect(within(record).getByText('거주 기간은 2년 이상')).toBeVisible();
    const details = screen.getByText('당시 조건·선택지와 근거 보기');
    expect(details.closest('details')).not.toHaveAttribute('open');
    await user.click(details);
    expect(
        within(record).getByText('2차 거주 기준을 확인했나요?'),
    ).toBeVisible();
    expect(
        within(record).getByText(entry(2).preparation.questions[0].reason),
    ).toBeVisible();
    expect(
        within(record).getByText('2차 거주 선택지 · 선택한 답변'),
    ).toBeVisible();
    expect(within(record).getByText('다른 지역')).toBeVisible();

    await user.click(screen.getByRole('button', { name: /안내 v1/ }));
    const old = await screen.findByRole('article', {
        name: '안내 v1 저장 기록',
    });
    expect(within(old).getByText('1차 메모')).toBeVisible();
    expect(within(old).getByText('거주 기간은 1년 이상')).toBeVisible();
    expect(
        within(old).queryByText('거주 기간은 2년 이상'),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('2차 메모')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /안내 v1/ })).toHaveAttribute(
        'aria-current',
        'true',
    );
    expect(preparationHistoryApi.get).toHaveBeenLastCalledWith(
        'preparation-a',
        1,
    );
});

it('서류명과 ID가 같아도 선택한 버전의 제출 대상·발급 방법·전체 안내를 표시한다', async () => {
    vi.mocked(preparationHistoryApi.get).mockImplementation(
        async (_, version) => {
            const detail = entry(version);
            const p = detail.preparation;
            p.documentConditions = { document: 'residence' };
            p.documentConditionValues = {
                document: [version === 2 ? 'LOCAL' : 'OTHER'],
            };
            p.documents[0].method = `첨부 ${version}호 양식을 작성하세요. 자세한 작성 예시는 당시 별지를 참고하세요.`;
            p.documents[0].issuer = `${version}차 발급처`;
            return detail;
        },
    );
    mount();
    const docs = within(
        await screen.findByRole('region', { name: '당시 서류와 메모' }),
    );
    expect(docs.getByText('2차 거주 선택지')).toBeVisible();
    expect(
        docs.getByText('2차 발급처 · 첨부 2호 양식을 작성하세요.'),
    ).toBeVisible();
    expect(docs.getByText(/자세한 작성 예시는/)).not.toBeVisible();
    await userEvent.click(docs.getByText('당시 발급 안내·근거 보기'));
    expect(docs.getByText(/자세한 작성 예시는/)).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: /안내 v1/ }));
    const old = within(
        await screen.findByRole('article', { name: '안내 v1 저장 기록' }),
    );
    const oldDocs = within(
        old.getByRole('region', { name: '당시 서류와 메모' }),
    );
    expect(oldDocs.getByText('다른 지역')).toBeVisible();
    expect(
        oldDocs.getByText('1차 발급처 · 첨부 1호 양식을 작성하세요.'),
    ).toBeVisible();
    expect(oldDocs.queryByText(/2차 발급처/)).toBeNull();
});
it('발급 기록이 비어 있으면 현재 발급 사이트를 과거 기록으로 표시하지 않는다', async () => {
    const detail = entry(1);
    Object.assign(detail.preparation.documents[0], {
        title: '주민등록등본',
        issuer: '',
        method: '',
    });
    vi.mocked(preparationHistoryApi.get).mockResolvedValue(detail);
    mount('?version=1');
    const docs = within(
        await screen.findByRole('region', { name: '당시 서류와 메모' }),
    );
    expect(docs.getByText('당시 발급 방법 기록 없음')).toBeVisible();
    expect(docs.getByText('당시 해당 없음')).toBeVisible();
    expect(docs.queryByText(/정부24/)).toBeNull();
});

it('주소에서 지정한 버전을 열고 존재하지 않는 버전은 다른 기록으로 바꾸지 않는다', async () => {
    const mounted = mount('?version=1');
    await screen.findByRole('article', { name: '안내 v1 저장 기록' });
    expect(preparationHistoryApi.get).toHaveBeenCalledWith('preparation-a', 1);
    mounted.unmount();
    vi.mocked(preparationHistoryApi.get).mockClear();
    mount('?version=99');
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '선택한 버전의 기록이 없습니다',
    );
    expect(preparationHistoryApi.get).not.toHaveBeenCalled();
});

it('이력이 없으면 현재 답변을 과거 기록으로 꾸미지 않으며 보관함으로 돌아간다', async () => {
    vi.mocked(preparationHistoryApi.list).mockResolvedValue({
        preparationId: 'preparation-a',
        title: '보관한 준비',
        archived: true,
        entries: [],
    });
    mount();
    expect(
        await screen.findByText('저장된 이전 안내가 없습니다'),
    ).toBeVisible();
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
        'href',
        '/archive',
    );
    expect(preparationHistoryApi.get).not.toHaveBeenCalled();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
});

it('과거 상세 조회 실패를 표시하고 같은 선택 버전을 재시도한다', async () => {
    const user = userEvent.setup();
    vi.mocked(preparationHistoryApi.get).mockRejectedValueOnce(
        new Error('기록 연결 실패'),
    );
    mount('?version=1');
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '기록 연결 실패',
    );
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByRole('article', { name: '안내 v1 저장 기록' });
    expect(preparationHistoryApi.get).toHaveBeenLastCalledWith(
        'preparation-a',
        1,
    );
});

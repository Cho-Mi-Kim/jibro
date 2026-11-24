import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Session } from '../../auth/useSession';
import { PreparationHistoryPage } from './PreparationHistoryPage';
import * as scrolling from '../../../shared/ui/animateScroll';
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
            title: `2026년 ${version}차 청년 전세임대주택 입주자 수시모집`,
            version,
            revision: 9,
            guideState: 'AVAILABLE',
            progress: version === 2 ? 65 : 20,
            houseStage: version === 2 ? 3 : 1,
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
                    necessity: 'REQUIRED',
                    state: version === 2 ? 'READY' : 'IN_PROGRESS',
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
                    title: '당시 공식 신청 여부 확인',
                    date: '2026-10-10',
                    recorded: version === 2,
                    evidence,
                },
            ],
        },
    };
}
beforeEach(() => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
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

it('최신 저장 버전부터 원래 답변·선택지·공통 서류·메모·일정 기록을 읽기 전용으로 보여 준다', async () => {
    const user = userEvent.setup();
    mount();
    const record = await screen.findByRole('article', {
        name: /2026\. 10\. 2\..*저장 기록/,
    });
    expect(
        within(record).getByRole('progressbar', { name: '당시 준비도' }),
    ).toHaveAttribute('value', '65');
    expect(
        within(record).getByRole('img', {
            name: '준비도 65% · 벽돌이 쌓이는 집',
        }),
    ).toBeVisible();
    expect(screen.getByText('2차 청년 전세임대')).toHaveAttribute(
        'title',
        entry(2).preparation.title,
    );
    expect(within(record).getByText('2차 거주 선택지')).toBeVisible();
    expect(within(record).getByText('2차 메모')).not.toBeVisible();
    await user.click(
        within(record).getByRole('heading', { name: '당시 서류' }),
    );
    const readyBadge = within(record).getByText('준비 완료');
    expect(readyBadge).toBeVisible();
    expect(readyBadge.parentElement).toHaveTextContent('준비 완료 · 공통 필수');
    expect(within(record).getByText('2차 메모')).toBeVisible();
    expect(within(record).getByText('신청 완료로 기록함')).toBeVisible();
    expect(within(record).getByText('2026.10.10')).toBeVisible();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
    expect(
        screen.queryByRole('link', { name: '현재에는 쓰지 않는 행동' }),
    ).not.toBeInTheDocument();
    expect(
        within(record).getByRole('heading', { name: '2차 거주 기준' }),
    ).toBeVisible();
    expect(
        within(record).getByText('거주 기간은 2년 이상 · 예외·별도 기준 있음'),
    ).toBeVisible();
    const details = screen.getByLabelText(/당시 조건·선택지와 근거 보기$/);
    expect(details.closest('details')).not.toHaveAttribute('open');
    await user.click(details);
    expect(
        within(record).getByText('2차 거주 기준을 확인했나요?'),
    ).toBeVisible();
    for (const text of [
        '거주 기간은 2년 이상이에요.',
        '주민등록상 주소로 확인해요.',
        '예외는 당시 안내를 따라요.',
    ])
        expect(within(record).getByText(text)).toBeVisible();
    expect(
        within(record).getByText('2차 거주 선택지 · 선택한 답변'),
    ).toBeVisible();
    expect(within(record).getByText('다른 지역')).toBeVisible();

    await user.click(screen.getByRole('button', { name: /2026\. 10\. 1\./ }));
    const old = await screen.findByRole('article', {
        name: /2026\. 10\. 1\..*저장 기록/,
    });
    expect(
        within(old).getByRole('progressbar', { name: '당시 준비도' }),
    ).toHaveAttribute('value', '20');
    expect(
        within(old).getByRole('img', { name: '준비도 20% · 벽돌이 쌓이는 집' }),
    ).toBeVisible();
    expect(screen.getByText('1차 청년 전세임대')).toHaveAttribute(
        'title',
        entry(1).preparation.title,
    );
    expect(screen.queryByText('2차 청년 전세임대')).toBeNull();
    await user.click(within(old).getByRole('heading', { name: '당시 서류' }));
    expect(within(old).getByText('준비 중')).toBeVisible();
    expect(within(old).queryByText('준비 완료')).toBeNull();
    expect(within(old).getByText('신청 완료 기록 없음')).toBeVisible();
    expect(within(old).getByText('1차 메모')).toBeVisible();
    expect(
        within(old).getByText('거주 기간은 1년 이상 · 예외·별도 기준 있음'),
    ).toBeVisible();
    expect(
        within(old).queryByText('거주 기간은 2년 이상 · 예외·별도 기준 있음'),
    ).not.toBeInTheDocument();
    expect(screen.queryByText('2차 메모')).not.toBeInTheDocument();
    expect(
        screen.getByRole('button', { name: /2026\. 10\. 1\./ }),
    ).toHaveAttribute('aria-current', 'true');
    expect(preparationHistoryApi.get).toHaveBeenLastCalledWith(
        'preparation-a',
        1,
    );
});

it('각 보관 기록의 답변으로 조건·서류·접수 회차를 추리고 원래 기록을 보존한다', async () => {
    const versions = [entry(1), entry(2)];
    for (const { version, preparation: p } of versions) {
        p.questions.push(
            {
                id: 'method',
                title: '어떤 방법으로 신청하나요?',
                reason: '인터넷 또는 방문 신청을 선택해요.',
                evidence,
                options: [
                    { value: 'ONLINE', label: '인터넷 신청' },
                    { value: 'VISIT', label: '현장 방문 신청' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            {
                id: 'work',
                title: '산업단지 추가 조건',
                reason: '산업단지 근로자 유형일 때 확인해요.',
                evidence,
                options: [
                    { value: 'MEETS', label: '기준에 맞아요' },
                    { value: 'NOT_MY_TYPE', label: '다른 유형으로 신청해요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            {
                id: 'guide',
                title: '공통 준비 안내',
                reason: '계약 전에 공식 안내를 확인해요.',
                evidence,
                options: [
                    { value: 'ACK', label: '안내를 확인했어요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        );
        p.answers.method = version === 2 ? 'ONLINE' : 'VISIT';
        p.answers.work = version === 2 ? 'NOT_MY_TYPE' : 'UNKNOWN';
        p.documentConditions = { visit: 'method', work: 'work' };
        p.documentConditionValues = { visit: ['VISIT'], work: ['MEETS'] };
        p.documents.push(
            {
                ...p.documents[0],
                id: 'visit',
                title: '방문 신청 확인 서류',
                necessity: version === 2 ? 'NOT_APPLICABLE' : 'CONDITIONAL',
            },
            {
                ...p.documents[0],
                id: 'work',
                title: '근로자 확인 서류',
                necessity: version === 2 ? 'NOT_APPLICABLE' : 'UNKNOWN',
            },
        );
        p.schedules.push(
            {
                id: 'end',
                title: '신청 접수 마감 (18:00)',
                date: '2026-10-12T18:00:00+09:00',
                recorded: true,
                evidence,
            },
            {
                id: 'online-end',
                title: '접수 회차 · 온라인 접수 마감',
                date: '2026-10-11T16:00:00+09:00',
                recorded: false,
                evidence,
            },
            {
                id: 'visit-end',
                title: '접수 회차 · 현장 접수 마감',
                date: '2026-10-12T18:00:00+09:00',
                recorded: false,
                evidence,
            },
        );
    }
    const originals = structuredClone(versions);
    vi.mocked(preparationHistoryApi.get).mockImplementation(
        async (_, version) =>
            versions.find((detail) => detail.version === version)!,
    );
    mount();
    for (const version of [2, 1]) {
        if (version === 1)
            await userEvent.click(
                screen.getByRole('button', { name: /2026\. 10\. 1\./ }),
            );
        const record = within(
            await screen.findByRole('article', {
                name:
                    version === 2
                        ? /2026\. 10\. 2\..*저장 기록/
                        : /2026\. 10\. 1\..*저장 기록/,
            }),
        );
        expect(
            record.getByRole('heading', { name: '공통 준비 안내' }),
        ).toBeVisible();
        expect(
            record.getByRole('heading', { name: '당시 서류' }),
        ).toBeVisible();
        if (version === 2) {
            expect(record.queryByText('산업단지 추가 조건')).toBeNull();
            expect(record.queryByText('방문 신청 확인 서류')).toBeNull();
            expect(record.queryByText('근로자 확인 서류')).toBeNull();
            expect(record.queryByText('접수 회차 · 현장 접수 마감')).toBeNull();
            expect(
                record.getByRole('heading', {
                    name: '접수 회차 · 온라인 접수 마감',
                }),
            ).toBeVisible();
        } else {
            expect(
                record.getByRole('heading', { name: '산업단지 추가 조건' }),
            ).toBeVisible();
            expect(
                record.getByRole('heading', { name: '방문 신청 확인 서류' }),
            ).toBeVisible();
            const unknown = record
                .getByRole('heading', { name: '근로자 확인 서류' })
                .closest('li')!;
            await userEvent.click(within(unknown).getByRole('heading'));
            expect(
                within(unknown).getByText(/해당 여부 확인 필요/),
            ).toBeVisible();
            expect(
                record.queryByText('접수 회차 · 온라인 접수 마감'),
            ).toBeNull();
            expect(
                record.getByRole('heading', {
                    name: '접수 회차 · 현장 접수 마감',
                }),
            ).toBeVisible();
        }
        const deadline = record
            .getByRole('heading', { name: '신청 접수 마감' })
            .closest('li')!;
        expect(
            within(deadline).getByText(
                version === 2 ? '2026.10.11 16:00' : '2026.10.12 18:00',
            ),
        ).toBeVisible();
        expect(within(deadline).getByText('완료로 기록함')).toBeVisible();
    }
    expect(versions).toEqual(originals);
});

it('같은 질문도 저장 버전별 자동차 한도와 당시 설명으로 요약한다', async () => {
    vi.mocked(preparationHistoryApi.get).mockImplementation(
        async (_, version) => {
            const detail = entry(version);
            const question = detail.preparation.questions[0];
            question.title = '세대 전체의 자동차 기준을 확인했나요?';
            const limit = version === 2 ? '5,451' : '3,708';
            question.reason = `자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 ${limit}만원 이하인지 확인합니다. 배우자 등 다른 세대원의 차량도 포함합니다.`;
            return detail;
        },
    );
    mount('?version=1');
    const record = within(
        await screen.findByRole('article', {
            name: /2026\. 10\. 1\..*저장 기록/,
        }),
    );
    expect(
        record.getByRole('heading', {
            name: '우리 집 자동차 기준은 얼마인가요?',
        }),
    ).toBeVisible();
    expect(
        record.getByText(
            '영업용이 아닌 승용차 중 가장 비싼 차가 3,708만 원 이하',
        ),
    ).toBeVisible();
    const explanation = record.getByText(
        /배우자 등 다른 세대원의 차도 함께 봐요/,
    );
    expect(explanation).not.toBeVisible();
    await userEvent.click(
        record.getByLabelText(/당시 조건·선택지와 근거 보기$/),
    );
    expect(explanation).toBeVisible();
    expect(record.queryByText(/5,451/)).not.toBeInTheDocument();
});

it('조건·서류·일정을 여닫으면 해당 기록으로 정렬하고 근거 링크는 펼침을 유지한다', async () => {
    const user = userEvent.setup();
    const positions: number[] = [];
    vi.spyOn(scrolling, 'animateScroll').mockImplementation((_, top) => {
        positions.push(typeof top === 'function' ? top() : top);
        return () => {};
    });
    mount();
    const record = within(await screen.findByRole('article'));
    const cards = [
        '2차 거주 기준',
        '당시 서류',
        '당시 공식 신청 여부 확인',
    ].map((name) => record.getByRole('heading', { name }).closest('li')!);
    for (const [index, card] of cards.entries()) {
        const top = 200 + index * 150;
        vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(
            new DOMRect(0, top, 360, 100),
        );
        positions.length = 0;
        const details = card.querySelector('details')!;
        await user.click(within(card).getByRole('heading'));
        expect(details.open).toBe(true);
        await waitFor(() => expect(positions).toEqual([top - 12]));
        expect(
            cards.filter((item) => item.querySelector('details[open]')),
        ).toEqual([card]);
        await user.click(within(card).getByRole('link'));
        expect(details.open).toBe(true);
        await user.click(card);
        expect(details.open).toBe(false);
        await waitFor(() => expect(positions).toEqual([top - 12, top - 12]));
    }
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
            p.answers.residence = version === 2 ? 'LOCAL' : 'OTHER';
            p.documents[0].necessity = 'CONDITIONAL';
            p.documents[0].method = `첨부 ${version}호 양식을 작성하세요. 자세한 작성 예시는 당시 별지를 참고하세요.`;
            p.documents[0].issuer = `${version}차 발급처`;
            p.documents[0].issueLabel = '저장한 공식 양식';
            p.documents[0].issueUrl = `https://apply.lh.or.kr/form-${version}.hwp`;
            return detail;
        },
    );
    mount();
    const docs = within(
        await screen.findByRole('region', { name: '당시 서류와 메모' }),
    );
    expect(docs.getByText('제출 대상')).not.toBeVisible();
    expect(docs.getByText('발급 방법')).not.toBeVisible();
    const title = docs.getByRole('heading', { name: '당시 서류' });
    await userEvent.click(title);
    expect(docs.getByText('2차 거주 선택지')).toBeVisible();
    expect(
        docs.getByRole('link', { name: '저장한 공식 양식' }),
    ).toHaveAttribute('href', 'https://apply.lh.or.kr/form-2.hwp');
    expect(
        docs.getByText('2차 발급처 · 첨부 2호 양식을 작성해요.'),
    ).toBeVisible();
    expect(docs.getByText(/자세한 작성 예시는/)).toBeVisible();
    await userEvent.click(title);
    expect(docs.getByText('2차 거주 선택지')).not.toBeVisible();
    expect(
        docs.getByText('2차 발급처 · 첨부 2호 양식을 작성해요.'),
    ).not.toBeVisible();
    await userEvent.click(
        screen.getByRole('button', { name: /2026\. 10\. 1\./ }),
    );
    const old = within(
        await screen.findByRole('article', {
            name: /2026\. 10\. 1\..*저장 기록/,
        }),
    );
    const oldDocs = within(
        old.getByRole('region', { name: '당시 서류와 메모' }),
    );
    await userEvent.click(oldDocs.getByRole('heading', { name: '당시 서류' }));
    expect(oldDocs.getByText('다른 지역')).toBeVisible();
    expect(
        oldDocs.getByText('1차 발급처 · 첨부 1호 양식을 작성해요.'),
    ).toBeVisible();
    expect(oldDocs.queryByText(/2차 발급처/)).toBeNull();
    expect(
        oldDocs.getByRole('link', { name: '저장한 공식 양식' }),
    ).toHaveAttribute('href', 'https://apply.lh.or.kr/form-1.hwp');
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
    await userEvent.click(docs.getByRole('heading', { name: '주민등록등본' }));
    expect(docs.getByText('당시 발급 방법 기록 없음')).toBeVisible();
    expect(docs.getByText('공통 필수', { exact: false })).toBeVisible();
    expect(docs.queryByText(/정부24/)).toBeNull();
});

it('주소에서 지정한 버전을 열고 존재하지 않는 버전은 다른 기록으로 바꾸지 않는다', async () => {
    const mounted = mount('?version=1');
    await screen.findByRole('article', { name: /2026\. 10\. 1\..*저장 기록/ });
    expect(preparationHistoryApi.get).toHaveBeenCalledWith('preparation-a', 1);
    mounted.unmount();
    vi.mocked(preparationHistoryApi.get).mockClear();
    mount('?version=99');
    expect(await screen.findByRole('alert')).toHaveTextContent(
        '선택한 준비 기록이 없습니다',
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
    await screen.findByRole('article', { name: /2026\. 10\. 1\..*저장 기록/ });
    expect(preparationHistoryApi.get).toHaveBeenLastCalledWith(
        'preparation-a',
        1,
    );
});

import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
    fireEvent,
    render,
    screen,
    waitFor,
    within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { Session } from '../features/auth/useSession';
import { api } from '../features/housing/api';
import { demoRequest, notices, resetDemo } from '../features/housing/demo';
import type {
    DocumentTemplate,
    Notice,
    Preparation,
    Question,
    User,
} from '../features/housing/types';
import { ConditionsPage } from '../pages/ConditionsPage';
import { DocumentsPage } from '../pages/DocumentsPage';
import { OneTimeCheckSession } from '../features/preparation/OneTimeCheckSession';
import { oneTimeCheckApi } from '../features/preparation/oneTimeCheckApi';
import { PreparationTabLayout } from '../features/preparation/PreparationTabLayout';
import { applicantInputQuestions } from '../features/housing/preparationApplicability';
import { conditionAnswerState } from '../features/housing/questions';

vi.mock('../shared/api/transport', () => ({
    demoMode: true,
    call: (path: string, method = 'GET', body?: unknown) =>
        demoRequest(path, method, body),
    clearToken: vi.fn(),
    setToken: vi.fn(),
}));

const official = JSON.parse(
    readFileSync(
        '../data/notices/pohang-bluevalley-answer-consistency-20261121.json',
        'utf8',
    ),
) as {
    content: { questions: Question[]; documents: DocumentTemplate[] };
};
const branchNotices = JSON.parse(
    readFileSync('src/app/fixtures/applicant-branch-notices.json', 'utf8'),
) as Notice[];
const refinedSangok = JSON.parse(
    readFileSync('src/app/fixtures/applicant-refined-sangok.json', 'utf8'),
) as Notice;
// Actual public metadata with the four reviewed candidates' complete question/document content.
const factNotices = JSON.parse(
    readFileSync('src/app/fixtures/applicant-fact-notices.json', 'utf8'),
) as Notice[];
const factDrafts = JSON.parse(
    readFileSync('../data/notices/held-guidance-20261123/batch-b.json', 'utf8'),
) as {
    noticeId: string;
    questionPatches: Question[];
    removedQuestionIds: string[];
    documentPatches: DocumentTemplate[];
}[];
// Produced from the complete official HWPX plus its three form bundles and facility brochure.
// The independent listing/PDF whole-notice coverage boundary is tested separately.
// ApplicantSituationBookletTest verifies this shared producer/consumer contract.
const generatedSangok = JSON.parse(
    readFileSync('src/app/fixtures/applicant-generated-sangok.json', 'utf8'),
) as {
    questions: Question[];
    documents: DocumentTemplate[];
    reviewIssues: NonNullable<Notice['reviewIssues']>;
};
const situationDrafts = JSON.parse(
    readFileSync(
        '../data/notices/all-applicant-situations-20261122/batch-c.json',
        'utf8',
    ),
) as {
    noticeId: string;
    questionPatches: Question[];
    documentPatches: DocumentTemplate[];
}[];

function useSituationNotice(noticeId: string) {
    const draft = situationDrafts.find((item) => item.noticeId === noticeId)!;
    notices[0].questions = structuredClone(draft.questionPatches);
    notices[0].documentTemplates = structuredClone(draft.documentPatches);
    notices[0].documents = draft.documentPatches.map(
        (document) => document.title,
    );
}

beforeEach(() => {
    resetDemo();
    notices[0].questions = structuredClone(official.content.questions);
    notices[0].documentTemplates = structuredClone(official.content.documents);
    notices[0].documents = notices[0].documentTemplates.map((d) => d.title);
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe() {}
            unobserve() {}
            disconnect() {}
        },
    );
});
afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
});

function useRefinedSangok() {
    Object.assign(notices[0], structuredClone(refinedSangok), {
        id: notices[0].id,
    });
}

function useDaeguFacts() {
    Object.assign(
        notices[0],
        structuredClone(
            factNotices.find(
                (notice) =>
                    notice.id === '0e185218-45e5-462d-8026-821bbf2528ee',
            ),
        ),
        { id: notices[0].id },
    );
}

function usePaju() {
    Object.assign(
        notices[0],
        structuredClone(
            branchNotices.find(
                (notice) =>
                    notice.id === '43637f47-27c6-409e-aaf4-f754c8b5df27',
            ),
        ),
        { id: notices[0].id },
    );
}

function mount(
    p?: Preparation,
    user?: User,
    search = 'question=household',
    entry: 'questions' | 'tab' | 'documents' = 'questions',
) {
    const cache = new QueryClient({
        defaultOptions: {
            queries: { retry: false },
            mutations: { retry: false },
        },
    });
    if (p && user) {
        cache.setQueryData(['preparation', user.id, p.id], p);
        cache.setQueryData(['notice', p.noticeId], notices[0]);
    }
    const page = render(
        <QueryClientProvider client={cache}>
            <Session.Provider
                value={{
                    user: user ?? null,
                    loading: false,
                    error: null,
                    refresh: async () => {},
                }}
            >
                <MemoryRouter
                    initialEntries={[
                        p
                            ? entry === 'documents'
                                ? `/preparations/${p.id}/documents`
                                : `/preparations/${p.id}/conditions${entry === 'tab' ? '' : `/edit?${search}`}`
                            : '/once',
                    ]}
                >
                    <Routes>
                        <Route element={<PreparationTabLayout />}>
                            <Route
                                path="/preparations/:preparationId/documents"
                                element={<DocumentsPage />}
                            />
                            <Route
                                path="/preparations/:preparationId/conditions"
                                element={<ConditionsPage mode="tab" />}
                            />
                        </Route>
                        <Route
                            path="/preparations/:preparationId/conditions/edit"
                            element={<ConditionsPage mode="questions" />}
                        />
                        <Route
                            path="/preparations/:preparationId/conditions/result"
                            element={<ConditionsPage mode="result" />}
                        />
                        <Route
                            path="/once"
                            element={
                                <OneTimeCheckSession notice={notices[0]} />
                            }
                        />
                    </Routes>
                </MemoryRouter>
            </Session.Provider>
        </QueryClientProvider>,
    );
    return { ...page, cache };
}

it('저장형 질문은 다른 유형의 질문을 건너뛰고 문맥 재확인·이전·다음·숨겨진 답변과 메모 보존을 실제 저장까지 연결한다', async () => {
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start('1', 1);
    p = await api.answers(p.id, p.revision, {
        category: 'INDUSTRIAL',
        household: 'MEETS',
        'income-assets': 'MEETS',
        work: 'MEETS',
    });
    p = await api.document(
        p.id,
        'work',
        p.revision,
        'IN_PROGRESS',
        '유형 변경 전 메모',
    );
    p = await api.answers(p.id, p.revision, {
        ...p.answers,
        category: 'YOUTH',
    });
    const page = mount(p, account);
    try {
        expect(
            await screen.findByRole('group', {
                name: '본인 명의의 집이 있나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 6개 중 2번째 질문')).toBeVisible();
        expect(
            within(
                screen.getByRole('complementary', { name: '질문 진행 단계' }),
            ).queryByText('산업단지 근로자 기준에 해당하나요?'),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('radio', {
                name: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
            }),
        ).toBeChecked();
        expect(
            screen.getByText(
                '부모 등 다른 가족의 집은 확인하지 않아요. 본인의 주택·분양권·입주권을 확인해 주세요.',
            ),
        ).toBeVisible();
        vi.spyOn(api, 'answers').mockRejectedValueOnce(
            new Error('일시적인 저장 실패'),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '허용 기준에 해당하지 않는 집이 있어요',
            }),
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            '일시적인 저장 실패',
        );
        expect(
            screen.getByRole('radio', {
                name: '허용 기준에 해당하지 않는 집이 있어요',
            }),
        ).toBeChecked();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            await screen.findByRole('radio', {
                name: '예비신혼부부 · 입주 전까지 혼인 예정',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '결혼 후 함께 살 가족에게 집이 있나요?',
            }),
        ).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        const changed = await api.preparation(p.id);
        expect(changed.answers).toEqual({
            category: 'PREMARITAL',
            household: 'UNKNOWN',
            'income-assets': 'UNKNOWN',
            work: 'MEETS',
        });
        expect(changed.documents.find((d) => d.id === 'work')).toMatchObject({
            note: '유형 변경 전 메모',
        });
        expect(changed.version).toBe(1);
        expect(
            within(
                screen.getByRole('complementary', { name: '질문 진행 단계' }),
            ).getAllByRole('listitem'),
        ).toHaveLength(6);
        await user.click(screen.getByRole('button', { name: '다음' }));
        expect(
            await screen.findByRole('group', { name: '자동차 기준에 맞나요?' }),
        ).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            await screen.findByRole('radio', {
                name: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
            }),
        );
        await user.click(
            await screen.findByRole('radio', {
                name: '자동차가 없거나, 한도·가산·제외 기준에 맞아요',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '배우자·가족 관계에서 내 상황은 무엇인가요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 6개 중 4번째 질문')).toBeVisible();
        const saved = await api.preparation(p.id);
        expect(saved.answers).toEqual({
            category: 'PREMARITAL',
            household: 'MEETS',
            'income-assets': 'MEETS',
            work: 'MEETS',
        });
        expect(saved.questions.map((q) => q.id)).toEqual([
            'category',
            'household',
            'income-assets',
            'work',
            'selection',
            'renewal',
            'applies-finance',
            'applies-assets',
            'applies-family',
            'applies-pregnancy',
            'applies-youth',
            'applies-marriage',
            'applies-work',
            'applies-subscription',
            'applies-proxy',
        ]);
        expect(saved.documents.find((d) => d.id === 'work')?.note).toBe(
            '유형 변경 전 메모',
        );
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('미확인 조건만 다시 답할 때 유형에 맞지 않는 미확인 질문은 건너뛰고 새로 미확인된 주택·자동차만 이어서 확인한다', async () => {
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start('1', 1);
    p = await api.answers(p.id, p.revision, {
        category: 'UNKNOWN',
        household: 'MEETS',
        'income-assets': 'MEETS',
        work: 'UNKNOWN',
        'applies-family': 'REQUIRED_TYPE',
        'applies-pregnancy': ['NONE'],
        'applies-proxy': 'SELF',
    });
    const page = mount(p, account, 'review=remaining&question=category');
    try {
        expect(
            await screen.findByLabelText('전체 2개 중 1번째 질문'),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '청년 · 만 19~39세, 미혼' }),
        );
        expect(
            await screen.findByRole('group', {
                name: '본인 명의의 집이 있나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 3개 중 2번째 질문')).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await user.click(
            screen.getByRole('radio', {
                name: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
            }),
        );
        expect(
            await screen.findByRole('group', { name: '자동차 기준에 맞나요?' }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 3개 중 3번째 질문')).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await user.click(
            screen.getByRole('radio', {
                name: '자동차가 없거나, 한도·가산·제외 기준에 맞아요',
            }),
        );
        await screen.findByRole('heading', { name: '추가로 확인할 조건' });
        expect((await api.preparation(p.id)).answers).toEqual({
            category: 'YOUTH',
            household: 'MEETS',
            'income-assets': 'MEETS',
            work: 'UNKNOWN',
            'applies-family': 'REQUIRED_TYPE',
            'applies-pregnancy': ['NONE'],
            'applies-proxy': 'SELF',
        });
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('일회성 질문은 청년 6개·산단과 모름 7개로 이전·다음과 유형 복귀를 맞추며 숨긴 답변을 보존하고 결과를 저장하지 않는다', async () => {
    const user = userEvent.setup();
    const evaluate = vi.spyOn(oneTimeCheckApi, 'evaluate');
    const save = vi.spyOn(api, 'answers');
    const start = vi.spyOn(api, 'start');
    const page = mount();
    try {
        expect(screen.getByLabelText('전체 7개 중 1번째 질문')).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '청년 · 만 19~39세, 미혼' }),
        );
        expect(
            await screen.findByRole('group', {
                name: '본인 명의의 집이 있나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 6개 중 2번째 질문')).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
            }),
        );
        expect(
            await screen.findByRole('group', { name: '자동차 기준에 맞나요?' }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '자동차가 없거나, 한도·가산·제외 기준에 맞아요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '배우자·가족 관계에서 내 상황은 무엇인가요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 6개 중 4번째 질문')).toBeVisible();
        await user.click(screen.getByRole('button', { name: '이전' }));
        expect(
            screen.getByRole('group', { name: '자동차 기준에 맞나요?' }),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(
            screen.getByRole('radio', {
                name: '청년·사회초년생·한부모·예비신혼부부로 신청해요',
            }),
        );
        await user.click(
            screen.getByRole('checkbox', {
                name: '태아·입양 자녀로 인정받는 경우가 아니에요',
            }),
        );
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(
            screen.getByRole('radio', { name: '내가 직접 신청해요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(evaluate).toHaveBeenLastCalledWith('1', 1, {
            category: 'YOUTH',
            household: 'MEETS',
            'income-assets': 'MEETS',
            'applies-family': 'REQUIRED_TYPE',
            'applies-pregnancy': ['NONE'],
            'applies-proxy': 'SELF',
        });
        await user.click(screen.getByRole('button', { name: '답변 수정' }));
        await user.click(
            screen.getByRole('radio', { name: '산업단지 근로자' }),
        );
        expect(
            await screen.findByRole('group', {
                name: '내 신청 유형의 주택 보유 기준에 맞나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 7개 중 2번째 질문')).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await user.click(screen.getByRole('button', { name: '다음' }));
        expect(
            screen.getByRole('group', { name: '자동차 기준에 맞나요?' }),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '다음' }));
        expect(
            screen.getByRole('group', {
                name: '산업단지 근로자 기준에 해당하나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 7개 중 4번째 질문')).toBeVisible();
        expect(
            screen
                .getAllByRole('radio')
                .every((radio) => !(radio as HTMLInputElement).checked),
        ).toBe(true);
        await user.click(
            screen.getByRole('radio', {
                name: '산업단지 근로자 기준에 맞아요',
            }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '등본에서 배우자를 확인할 수 없어요',
            }),
        );
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(screen.getByRole('button', { name: '이전' }));
        expect(
            screen.getByRole('radio', {
                name: '산업단지 근로자 기준에 맞아요',
            }),
        ).toBeChecked();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(screen.getByRole('radio', { name: '잘 모르겠어요' }));
        expect(
            await screen.findByRole('group', {
                name: '내 신청 유형의 주택 보유 기준에 맞나요?',
            }),
        ).toBeVisible();
        expect(screen.getByLabelText('전체 7개 중 2번째 질문')).toBeVisible();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '신혼부부 · 혼인 10년 이내 또는 9세 이하 자녀',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '신청자와 심사에 포함되는 가족에게 집이 있나요?',
            }),
        ).toBeVisible();
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        expect(
            within(
                screen.getByRole('complementary', { name: '질문 진행 단계' }),
            ).getAllByRole('listitem'),
        ).toHaveLength(6);
        await user.click(screen.getByRole('button', { name: '다음' }));
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '없거나, 공고에서 허용하는 소형·저가주택 등이 있어요',
            }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '자동차가 없거나, 한도·가산·제외 기준에 맞아요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '배우자·가족 관계에서 내 상황은 무엇인가요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(screen.getByRole('button', { name: '결과 확인' }));
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(evaluate).toHaveBeenLastCalledWith('1', 1, {
            category: 'NEWLYWED',
            household: 'MEETS',
            'income-assets': 'MEETS',
            work: 'MEETS',
            'applies-family': 'SEPARATE_SPOUSE',
            'applies-pregnancy': ['NONE'],
            'applies-proxy': 'SELF',
        });
        expect(save).not.toHaveBeenCalled();
        expect(start).not.toHaveBeenCalled();
        expect(page.cache.getQueryCache().getAll()).toEqual([]);
        await api.login('demo@example.invalid', 'demo');
        expect(await api.preparations()).toEqual([]);
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('춘천·철원은 뒤에 있던 신청 유형부터 묻고 청년에서 대학생으로 바꿔도 공통 질문·저장 답변·서류 개수를 일치시킨다', async () => {
    Object.assign(
        notices[0],
        structuredClone(
            branchNotices.find(
                (notice) =>
                    notice.id === 'd766f8da-4856-46ab-b22f-055191f22646',
            ),
        ),
        // Bind the public content to the demo API's existing reviewed notice record.
        { id: notices[0].id },
    );
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    p = await api.answers(p.id, p.revision, {
        site: 'YES',
        youth: 'YES',
        priority: 'YES',
        apply: 'YES',
        'document-situation': ['NONE'],
        'special-proof': ['NONE'],
        'birth-allowance': 'NO',
    });
    let page = mount(p, account, '', 'tab');
    try {
        const continueLink = await screen.findByRole('link', {
            name: '준비 이어하기',
        });
        expect(continueLink).toHaveAttribute(
            'href',
            `/preparations/${p.id}/conditions/edit?question=application-category`,
        );
        await user.click(continueLink);
        await screen.findByRole('group', {
            name: '어떤 유형으로 신청할 예정인가요?',
        });
        expect(screen.getByLabelText('전체 11개 중 1번째 질문')).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '19~39세 청년' }));
        await screen.findByRole('group', {
            name: '신청할 단지에 내 유형이 있나요?',
        });
        expect(screen.getByLabelText('전체 8개 중 2번째 질문')).toBeVisible();
        const steps = within(
            screen.getByRole('complementary', { name: '질문 진행 단계' }),
        );
        expect(
            steps.getByText('청년·사회초년생 기준을 확인했나요?'),
        ).toBeVisible();
        expect(
            steps.queryByText('대학생·취업준비생 기준을 확인했나요?'),
        ).not.toBeInTheDocument();
        expect(
            steps.queryByText('신혼·한부모 신청 유형을 확인했나요?'),
        ).not.toBeInTheDocument();
        expect(
            steps.queryByText('고령자·주거급여 유형의 기준은 무엇인가요?'),
        ).not.toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(screen.getByRole('radio', { name: '대학 재학 중' }));
        await screen.findByRole('group', {
            name: '신청할 단지에 내 유형이 있나요?',
        });
        await user.click(screen.getByRole('button', { name: '다음' }));
        await screen.findByRole('group', {
            name: '대학생·취업준비생 기준을 확인했나요?',
        });
        await user.click(
            screen.getByRole('radio', { name: '이 유형의 기준을 확인했어요' }),
        );
        await screen.findByRole('group', {
            name: '우선 공급 대상과 단지별 순서를 확인했나요?',
        });
        await user.click(screen.getByRole('button', { name: '이전' }));
        expect(
            screen.getByRole('radio', { name: '이 유형의 기준을 확인했어요' }),
        ).toBeChecked();
        p = await api.preparation(p.id);
        expect(p.answers).toEqual({
            site: 'YES',
            youth: 'YES',
            student: 'YES',
            priority: 'YES',
            apply: 'YES',
            'application-category': 'STUDENT',
            'document-situation': ['NONE'],
            'special-proof': ['NONE'],
            'birth-allowance': 'NO',
        });
        expect(p.questions[0].id).toBe('site');
        expect(p.questions).toHaveLength(11);
        expect(p.warnings).toEqual([]);
        page.unmount();
        page.cache.clear();
        page = mount(p, account, 'question=birth-allowance');
        await user.click(
            await screen.findByRole('radio', {
                name: '가산을 적용하지 않아요',
            }),
        );
        await screen.findByText('추가 확인으로 남긴 조건이 없습니다.');
        expect(screen.getByText('답변 완료 8개 보기')).toBeVisible();
        const documents = within(
            screen.getByRole('complementary', { name: '개인별 서류' }),
        );
        expect(documents.getByText('필요 서류 7개')).toBeVisible();
        expect(documents.getByText('해당 여부 추가 확인 0개')).toBeVisible();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('울산다운2의 사회초년생 별칭도 같은 유형 질문으로 연결하고 공통 서류와 공식 검수만 결과에 남긴다', async () => {
    Object.assign(
        notices[0],
        structuredClone(
            branchNotices.find(
                (notice) =>
                    notice.id === 'a660a69d-51e6-40b5-b057-044583dd8b81',
            ),
        ),
        { id: notices[0].id },
    );
    const user = userEvent.setup();
    const evaluate = vi.spyOn(oneTimeCheckApi, 'evaluate');
    const save = vi.spyOn(api, 'answers');
    const page = mount();
    try {
        expect(
            screen.getByRole('group', {
                name: '어떤 유형으로 신청할 예정인가요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '일하는 사회초년생 · 소득활동 7년 이내',
            }),
        );
        expect(screen.getByLabelText('전체 6개 중 2번째 질문')).toBeVisible();
        expect(
            screen.getByRole('group', {
                name: '계약 후 자격 확인 방식을 확인했나요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '기준을 확인했어요' }),
        );
        expect(
            screen.getByRole('group', {
                name: '청년·사회초년생 기준을 확인했나요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '기준을 확인했어요' }),
        );
        expect(
            screen.getByRole('group', {
                name: '가족관계증명서를 내야 하는 경우인가요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '신청 유형이나 가족 상황상 필요해요',
            }),
        );
        await user.click(
            screen.getByRole('checkbox', { name: '해당 사항이 없어요' }),
        );
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(
            screen.getByRole('radio', { name: '온라인으로 신청해요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(screen.getByText('답변 완료 6개 · 추가 확인 1개')).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '확인할 서류 10개' }),
        ).toBeVisible();
        expect(evaluate).toHaveBeenLastCalledWith(
            notices[0].id,
            notices[0].version,
            {
                'review-category': 'WORKER',
                verification: 'YES',
                youth: 'YES',
                'review-family': 'YES',
                'review-extra-documents': ['NONE'],
                'review-visit': 'ONLINE',
            },
        );
        expect(save).not.toHaveBeenCalled();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('신혼 전세임대의 혼인가구는 한부모 전용 질문·서류를 미완료로 남기지 않고 실제 가족상태와 공통 질문은 유지한다', async () => {
    Object.assign(
        notices[0],
        structuredClone(
            branchNotices.find(
                (notice) =>
                    notice.id === '11f5a8d1-3100-4d80-8f4b-b0ef6dc8af3c',
            ),
        ),
        { id: notices[0].id },
    );
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    p = await api.answers(p.id, p.revision, {
        'family-new2-adult': 'ADULT',
        'family-new2-category': 'UNKNOWN',
        'family-new2-household': ['NONE'],
        'family-new2-housing': 'NONE',
        'family-new2-support': 'NONE',
        'family-new2-child-evidence': ['NONE'],
        'family-new2-income': 'YES',
        'family-new2-assets': 'YES',
        'family-new2-finance': ['REQUIRED'],
        'family-new2-foreign-document': 'NO',
        'family-new2-marriage-doc': 'MARRIED',
        'family-new2-parent-separated': 'UNKNOWN',
        'family-new2-rank': 'YES',
        'family-new2-scans': 'YES',
        'family-new2-application': 'YES',
        'family-new2-home-search': 'YES',
        'family-new2-after-selection': 'YES',
    });
    const page = mount(
        p,
        account,
        'review=remaining&question=family-new2-category',
    );
    try {
        expect(
            await screen.findByLabelText('전체 2개 중 1번째 질문'),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '혼인7년 이내 신혼부부' }),
        );
        await screen.findByText('추가 확인으로 남긴 조건이 없습니다.');
        expect(screen.getByText('답변 완료 16개 보기')).toBeVisible();
        const documents = within(
            screen.getByRole('complementary', { name: '개인별 서류' }),
        );
        expect(documents.getByText('필요 서류 5개')).toBeVisible();
        expect(documents.getByText('해당 여부 추가 확인 0개')).toBeVisible();
        const saved = await api.preparation(p.id);
        expect(saved.answers['family-new2-parent-separated']).toBe('UNKNOWN');
        expect(saved.answers['family-new2-marriage-doc']).toBe('MARRIED');
        expect(saved.warnings).toEqual([]);
        expect(saved.questions).toHaveLength(17);
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('원문으로 나눈 산곡의 저장형 질문은 예비신혼의 미래 세대만 묻고 완료·메모와 다른 유형의 원답변을 보존한다', async () => {
    useRefinedSangok();
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    p = await api.answers(p.id, p.revision, {
        rank: 'YES',
        'applies-work': 'NO',
        'applies-child': 'NO',
        'applies-foreign': 'NO',
        'applies-proxy': 'NO',
        'newborn-priority': 'YES',
        'application-type': 'UNKNOWN',
        'household-current': 'UNKNOWN',
        'income-assets-married': 'UNKNOWN',
    });
    p = await api.document(
        p.id,
        'family',
        p.revision,
        'READY',
        '발급한 가족 서류',
    );
    const page = mount(
        p,
        account,
        'review=remaining&question=application-type',
    );
    try {
        await user.click(
            await screen.findByRole('radio', {
                name: '예비신혼부부 · 입주 전 결혼할 예정이에요',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '결혼 후 함께 살 가족의 주택 기준에 맞나요?',
            }),
        ).toBeVisible();
        const steps = within(
            screen.getByRole('complementary', { name: '질문 진행 단계' }),
        );
        expect(
            steps.queryByTitle('신청자와 심사 가족의 주택 기준에 맞나요?'),
        ).not.toBeInTheDocument();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        expect(
            await screen.findByRole('group', {
                name: '결혼 후 세대의 소득·자산 기준에 맞나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        expect(
            await screen.findByRole('group', {
                name: '두 사람 중 한 명이 청약통장에 가입하나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        await screen.findByText('추가 확인으로 남긴 조건이 없습니다.');
        const saved = await api.preparation(p.id);
        expect(saved.answers['household-current']).toBe('UNKNOWN');
        expect(saved.answers['household-future']).toBe('MEETS');
        expect(saved.warnings).toEqual([]);
        expect(
            saved.documents.find((d) => d.id === 'newlywed')?.necessity,
        ).toBe('CONDITIONAL');
        expect(saved.documents.find((d) => d.id === 'family')).toMatchObject({
            state: 'READY',
            note: '발급한 가족 서류',
        });
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('산곡 일회성은 유형을 다시 고르면 맞는 가족·소득·통장 질문으로 바뀌고 공통 서류만 함께 결과에 남긴다', async () => {
    useRefinedSangok();
    const user = userEvent.setup();
    const evaluate = vi.spyOn(oneTimeCheckApi, 'evaluate');
    const save = vi.spyOn(api, 'answers');
    const page = mount();
    try {
        await user.click(
            screen.getByRole('radio', {
                name: '예비신혼부부 · 입주 전 결혼할 예정이에요',
            }),
        );
        let steps = within(
            screen.getByRole('complementary', { name: '질문 진행 단계' }),
        );
        expect(
            steps.getByTitle('결혼 후 함께 살 가족의 주택 기준에 맞나요?'),
        ).toBeInTheDocument();
        expect(
            steps.queryByTitle('신청자와 심사 가족의 주택 기준에 맞나요?'),
        ).not.toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '한부모가족 · 6세 이하 자녀가 있어요',
            }),
        );
        steps = within(
            screen.getByRole('complementary', { name: '질문 진행 단계' }),
        );
        expect(
            steps.queryByTitle('결혼 후 함께 살 가족의 주택 기준에 맞나요?'),
        ).not.toBeInTheDocument();
        expect(
            steps.getByTitle('신청자와 심사 가족의 주택 기준에 맞나요?'),
        ).toBeInTheDocument();
        await user.click(screen.getByRole('radio', { name: '확인했어요' }));
        for (let i = 0; i < 4; i++)
            await user.click(
                screen.getByRole('radio', { name: '제출 대상이 아니에요' }),
            );
        expect(
            screen.getByRole('group', {
                name: '신청자와 심사 가족의 주택 기준에 맞나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        expect(
            screen.getByRole('group', {
                name: '한부모가족 소득·자산 기준에 맞나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        expect(
            screen.getByRole('group', {
                name: '신청자 본인이 청약통장에 가입하나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('radio', { name: '기준에 맞아요' }));
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', { name: '확인할 서류 8개' }),
        ).toBeVisible();
        expect(evaluate).toHaveBeenLastCalledWith(
            notices[0].id,
            notices[0].version,
            {
                'application-type': 'SINGLE_PARENT',
                rank: 'YES',
                'applies-work': 'NO',
                'applies-child': 'NO',
                'applies-foreign': 'NO',
                'applies-proxy': 'NO',
                'household-current': 'MEETS',
                'income-assets-single-parent': 'MEETS',
                'subscription-single-parent': 'MEETS',
            },
        );
        expect(save).not.toHaveBeenCalled();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('파주는 성년·세 미성년 예외·비해당·모름에 맞는 질문만 남기고 비해당의 확인 필요를 유지한다', () => {
    usePaju();
    const questions = notices[0].questions;
    const common = [
        'family-register',
        'household-application',
        'existing-contract',
        'sublet-history',
        'review-register-changed',
    ];
    const cases: [string, string[]][] = [
        ['ADULT', common],
        ['MINOR_CHILD', ['minor-residence-housing', ...common]],
        [
            'MINOR_SIBLINGS',
            ['minor-residence-housing', 'minor-sibling-cause', ...common],
        ],
        [
            'MINOR_FOREIGN_PARENT',
            ['minor-residence-housing', 'minor-parent-application', ...common],
        ],
        ['NONE', ['eligibility-recheck']],
        [
            'UNKNOWN',
            [
                'minor-residence-housing',
                'minor-sibling-cause',
                'minor-parent-application',
                'eligibility-recheck',
                ...common,
            ],
        ],
    ];
    for (const [answer, expected] of cases) {
        expect(
            applicantInputQuestions({
                questions,
                answers: { 'applicant-situation': answer },
            }).map((q) => q.id),
        ).toEqual(['applicant-situation', ...expected]);
    }
    const recheck = questions.find((q) => q.id === 'eligibility-recheck')!;
    for (const answer of [
        'RESIDENCE',
        'HOUSING',
        'AGE_EXCEPTION',
        'NATIONALITY',
        'UNKNOWN',
    ]) {
        expect(conditionAnswerState(recheck, answer)).toBe('followUp');
    }
});

it('파주의 저장형 입력은 형제자매 부양 예외의 다음 질문과 신고접수증을 연결하고 기존 메모를 보존한다', async () => {
    usePaju();
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    p = await api.answers(p.id, p.revision, {
        'applicant-situation': 'UNKNOWN',
        'family-register': 'SAME_REGISTER',
        'household-application': 'ONLY_ME',
        'existing-contract': 'NONE',
        'sublet-history': 'NO',
        'review-register-changed': 'NO',
    });
    p = await api.document(
        p.id,
        's1-file-38-2-0',
        p.revision,
        'READY',
        '기존 등본 메모',
    );
    const page = mount(
        p,
        account,
        'review=remaining&question=applicant-situation',
    );
    try {
        await user.click(
            await screen.findByRole('radio', {
                name: '미성년 세대주로, 부모·조부모 등의 사망·실종·행방불명으로 같은 등본의 형제자매를 부양해요',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '수도권에 살고, 함께 심사하는 가족 모두 무주택인가요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '수도권에 살고, 심사 대상 가족 모두 무주택이에요',
            }),
        );
        expect(
            await screen.findByRole('group', {
                name: '어떤 사유로 형제자매를 부양하나요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '부모·조부모 등이 행방불명 상태예요',
            }),
        );
        await screen.findByText('추가 확인으로 남긴 조건이 없습니다.');
        const saved = await api.preparation(p.id);
        expect(saved.answers['minor-sibling-cause']).toBe('MISSING_REPORT');
        expect(saved.answers['minor-parent-application']).toBeUndefined();
        expect(
            saved.documents.find((d) => d.id === 'minor-missing-report')
                ?.necessity,
        ).toBe('CONDITIONAL');
        expect(
            saved.documents.find((d) => d.id === 's1-file-38-2-0'),
        ).toMatchObject({ state: 'READY', note: '기존 등본 메모' });
        expect(saved.warnings).toEqual([]);
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('파주 일회성은 성년 선택을 미성년 외국인 부모 예외로 바꾸면 해당 질문을 묻고 부모와 배우자 서류를 혼동하지 않는다', async () => {
    usePaju();
    const user = userEvent.setup();
    const save = vi.spyOn(api, 'answers');
    const page = mount();
    try {
        await user.click(
            screen.getByRole('radio', {
                name: '수도권에 사는 무주택 세대의 만 19세 이상 한국 국적 성년자예요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '배우자와 등본은 어떤 상황인가요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '외국인 부모와 사는 한부모가족의 한국 국적 미성년 세대주예요',
            }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '수도권에 살고, 심사 대상 가족 모두 무주택이에요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '외국인 부모가 대신 신청하나요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', {
                name: '외국인 부모가 대신 신청할 예정이에요',
            }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '배우자가 없어요 · 미혼·이혼·사별',
            }),
        );
        await user.click(screen.getByRole('radio', { name: '저만 신청해요' }));
        await user.click(
            screen.getByRole('radio', {
                name: '기계약자가 없고, 재당첨 제한 중인 세대원도 없어요',
            }),
        );
        await user.click(screen.getByRole('radio', { name: '없어요' }));
        await user.click(screen.getByRole('radio', { name: '변경이 없어요' }));
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', { name: '확인할 서류 3개' }),
        ).toBeVisible();
        expect(
            screen.queryByText('외국인 배우자 등록·거소 확인 서류'),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByText('행방불명 신고접수증'),
        ).not.toBeInTheDocument();
        expect(save).not.toHaveBeenCalled();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('신혼 전세임대의 저장형은 가족 유형부터 묻고 모순된 가족 답변에도 예비신혼 필수 서류를 유지한다', async () => {
    useSituationNotice('11f5a8d1-3100-4d80-8f4b-b0ef6dc8af3c');
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    const page = mount(p, account, '');
    try {
        await user.click(
            await screen.findByRole('radio', {
                name: '입주 전 혼인할 예비신혼부부',
            }),
        );
        await user.click(
            await screen.findByRole('radio', { name: '만 19세 이상이에요' }),
        );
        await user.click(
            await screen.findByRole('radio', {
                name: '한부모 · 가족관계증명서 준비',
            }),
        );
        expect(
            screen.getByText(
                '앞에서 선택한 상황과 다른 답변이에요. 필요한 서류가 빠지지 않도록 두 답변을 다시 확인해 주세요.',
            ),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '다음' }));
        await waitFor(async () => {
            p = await api.preparation(p.id);
            expect(p.answers['family-new2-marriage-doc']).toBe('SINGLE_PARENT');
        });
        p = await api.preparation(p.id);
        expect(p.answers['family-new2-category']).toBe('PREMARITAL');
        expect(p.answers['family-new2-marriage-doc']).toBe('SINGLE_PARENT');
        expect(p.answers['family-new2-minor-contract']).toBeUndefined();
        expect(p.warnings.length).toBeGreaterThan(0);
        for (const id of [
            'family-new2-pre-household',
            'family-new2-pre-delegate',
            'family-new2-pre-confirm',
            'family-new2-pre-id',
        ]) {
            expect(
                p.documents.find((document) => document.id === id)?.necessity,
            ).not.toBe('NOT_APPLICABLE');
        }
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('고령자복지형 일회성은 단독 입주 여부에 맞는 주택 질문으로 바뀌고 소득·자산 공통 질문은 유지한다', async () => {
    useSituationNotice('e91cd733-2195-41df-91e9-954dacff6993');
    const user = userEvent.setup();
    const save = vi.spyOn(api, 'answers');
    const page = mount();
    try {
        await user.click(
            screen.getByRole('radio', {
                name: '만 65세 이상 · 혼인 중이 아니고 단독세대주로 입주해요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '본인 명의의 집이나 분양권 등이 있나요?',
            }),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '만 65세 이상 · 그 밖의 가구 상황이에요',
            }),
        );
        expect(
            screen.getByRole('group', {
                name: '함께 심사하는 가족 모두 집이나 분양권 등이 없나요?',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('radio', { name: '소유한 집이 없어요' }),
        );
        expect(screen.getByRole('group', { name: /소득.*자산/ })).toBeVisible();
        expect(save).not.toHaveBeenCalled();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

function useGeneratedSangok() {
    notices[0].reviewIssues = structuredClone(generatedSangok.reviewIssues);
    notices[0].questions = structuredClone(generatedSangok.questions);
    notices[0].documentTemplates = structuredClone(generatedSangok.documents);
    notices[0].documents = generatedSangok.documents.map(
        (document) => document.title,
    );
}

it('공식 원문을 대조한 네 공고의 화면 자료는 게시 원고의 질문·서류 연결과 일치한다', () => {
    for (const draft of factDrafts) {
        const notice = factNotices.find((item) => item.id === draft.noticeId)!;
        for (const question of draft.questionPatches)
            expect(
                notice.questions.find((item) => item.id === question.id),
            ).toEqual(question);
        for (const document of draft.documentPatches)
            expect(
                notice.documentTemplates?.find(
                    (item) => item.id === document.id,
                ),
            ).toEqual(document);
        expect(
            notice.questions.some((question) =>
                draft.removedQuestionIds.includes(question.id),
            ),
        ).toBe(false);
    }
});

it('대구의 실제 상황 질문은 온라인에서 배우자 현장 신청으로 바꾸면 공통 서류와 신청서·대리서류를 저장형 화면에 맞춘다', async () => {
    useDaeguFacts();
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    let p = await api.start(notices[0].id, notices[0].version);
    p = await api.answers(p.id, p.revision, {
        'applicant-situation': 'ADULT',
        household: 'YES',
        vehicle: 'YES',
        contract: 'YES',
        restriction: 'YES',
        selection: 'YES',
        'contract-selection-fact': 'NOT_SELECTED',
        'family-relationship-fact': 'NONE',
        'history-fact': 'NO',
        'premarital-fact': 'NO',
        'foreign-spouse-fact': 'NO',
        'visit-submitter': 'ONLINE',
    });
    p = await api.document(
        p.id,
        'resident',
        p.revision,
        'IN_PROGRESS',
        '발급할 때 표시 항목 확인',
    );
    const before = mount(p, account, '', 'documents');
    try {
        expect(
            await screen.findByRole('button', { name: '주민등록표등본(상세)' }),
        ).toBeVisible();
        expect(
            screen.queryByRole('button', { name: '국민임대주택 공급신청서' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: '현장·대리신청 확인서류' }),
        ).not.toBeInTheDocument();
    } finally {
        before.unmount();
        before.cache.clear();
    }
    const input = mount(p, account, 'question=visit-submitter');
    try {
        expect(
            await screen.findByRole('radio', {
                name: '현장에 가지 않고 온라인으로 신청해요',
            }),
        ).toBeChecked();
        await user.click(
            screen.getByRole('radio', { name: '배우자가 대신 가요' }),
        );
        await waitFor(async () => {
            p = await api.preparation(p.id);
            expect(p.answers['visit-submitter']).toBe('SPOUSE');
        });
    } finally {
        input.unmount();
        input.cache.clear();
    }
    const after = mount(p, account, '', 'documents');
    try {
        expect(
            await screen.findByRole('button', {
                name: '국민임대주택 공급신청서',
            }),
        ).toBeVisible();
        await user.click(
            screen.getByRole('button', { name: '현장·대리신청 확인서류' }),
        );
        expect(screen.getByText(/배우자.*신분증/)).toBeVisible();
        expect(
            screen.getByRole('button', { name: '주민등록표등본(상세)' }),
        ).toBeVisible();
        expect(
            p.documents.find((document) => document.id === 'resident'),
        ).toMatchObject({
            necessity: 'REQUIRED',
            note: '발급할 때 표시 항목 확인',
        });
        expect(
            p.documents.find((document) => document.id === 'application')
                ?.necessity,
        ).toBe('CONDITIONAL');
        expect(
            p.documents.find((document) => document.id === 'identity')
                ?.necessity,
        ).toBe('CONDITIONAL');
    } finally {
        after.unmount();
        after.cache.clear();
    }
});

it('대구 일회성은 예비신혼 모순의 필수 서류를 남기고 온라인 비해당 서류를 숨기며 답변·준비 기록을 저장하지 않는다', async () => {
    useDaeguFacts();
    const user = userEvent.setup();
    const evaluate = vi.spyOn(oneTimeCheckApi, 'evaluate');
    const save = vi.spyOn(api, 'answers');
    const start = vi.spyOn(api, 'start');
    const page = mount();
    try {
        await user.click(
            screen.getByRole('radio', { name: '만 19세 이상 성년자예요' }),
        );
        for (let i = 0; i < 5; i++)
            await user.click(screen.getByRole('radio', { name: '확인했어요' }));
        await user.click(
            screen.getByRole('radio', { name: '선정되지 않았어요' }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '예비신혼이며 결혼 후 두 사람 외 가족과 함께 살아요',
            }),
        );
        await user.click(
            screen.getByRole('radio', { name: '둘 다 해당하지 않아요' }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '예비신혼부부로 신청하지 않아요',
            }),
        );
        expect(
            screen.getByText(
                '가족관계에서 예비신혼부부라고 답했어요. 예비신혼 신청 서류와 대표 신청자의 초본이 빠지지 않도록 두 답변을 다시 확인해 주세요.',
            ),
        ).toBeVisible();
        await user.click(screen.getByRole('button', { name: '다음' }));
        await user.click(
            screen.getByRole('radio', { name: '외국인 배우자가 없어요' }),
        );
        await user.click(
            screen.getByRole('radio', {
                name: '현장에 가지 않고 온라인으로 신청해요',
            }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', {
                name: '예비신혼부부 신청·세대구성 확인서',
            }),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '주민등록표초본(상세)' }),
        ).toBeVisible();
        expect(
            screen.queryByRole('heading', { name: '국민임대주택 공급신청서' }),
        ).not.toBeInTheDocument();
        expect(
            screen.queryByRole('heading', { name: '현장·대리신청 확인서류' }),
        ).not.toBeInTheDocument();
        expect(
            screen.getByRole('heading', { name: '주민등록표등본(상세)' }),
        ).toBeVisible();
        const result = await evaluate.mock.results.at(-1)!.value;
        expect(result.answers['premarital-fact']).toBe('NO');
        expect(result.answers['family-relationship-fact']).toBe(
            'PREMARITAL_EXTRA',
        );
        expect(
            result.documents.find(
                (document: { id: string }) => document.id === 'newlywed',
            )?.necessity,
        ).toBe('CONDITIONAL');
        expect(
            result.documents.find(
                (document: { id: string }) => document.id === 'history',
            )?.necessity,
        ).toBe('CONDITIONAL');
        expect(result.warnings.length).toBeGreaterThan(0);
        expect(save).not.toHaveBeenCalled();
        expect(start).not.toHaveBeenCalled();
        expect(page.cache.getQueryCache().getAll()).toHaveLength(0);
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('실제 공식 번호 절에서 자동 생성한 상황은 저장형 입력과 준비 서류에 연결된다', async () => {
    useGeneratedSangok();
    const user = userEvent.setup();
    const account = await api.login('demo@example.invalid', 'demo');
    const p = await api.start('1', 1);
    const page = mount(p, account, 'question=applicant-situation');
    try {
        await user.click(
            await screen.findByRole('radio', {
                name: '신혼부부 · 혼인중인 자',
            }),
        );
        await waitFor(() =>
            expect(
                screen.getByRole('complementary', { name: '질문 진행 단계' }),
            ).not.toHaveTextContent('예비신혼부부 신청 기준 ⑥'),
        );
        let steps = screen.getByRole('complementary', {
            name: '질문 진행 단계',
        });
        expect(steps).toHaveTextContent('신혼부부 신청 기준 ②');
        expect(steps).not.toHaveTextContent('예비신혼부부 신청 기준 ⑥');
        let saved = await api.preparation(p.id);
        expect(saved.answers['applicant-situation']).toBe('NEWLYWED');
        expect(
            saved.documents.find((d) =>
                d.title.includes('예비신혼부부 신청 확인서'),
            )?.necessity,
        ).toBe('NOT_APPLICABLE');
        expect(
            saved.documents.find((d) => d.title === '가족관계증명서')
                ?.necessity,
        ).toBe('REQUIRED');
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            await screen.findByRole('radio', {
                name: '예비신혼부부 · 혼인을 계획 중이며 입주 전까지 혼인사실을 증명할 수 있을 것',
            }),
        );
        await waitFor(() =>
            expect(
                screen.getByRole('complementary', { name: '질문 진행 단계' }),
            ).not.toHaveTextContent('신혼부부 신청 기준 ②'),
        );
        steps = screen.getByRole('complementary', { name: '질문 진행 단계' });
        expect(steps).not.toHaveTextContent('신혼부부 신청 기준 ②');
        expect(steps).toHaveTextContent('예비신혼부부 신청 기준 ⑥');
        saved = await api.preparation(p.id);
        expect(
            saved.documents.find((d) =>
                d.title.includes('예비신혼부부 신청 확인서'),
            )?.necessity,
        ).toBe('CONDITIONAL');
        expect(
            saved.documents.find((d) => d.title === '가족관계증명서')
                ?.necessity,
        ).toBe('REQUIRED');
        page.unmount();
        const documents = mount(saved, account, '', 'documents');
        try {
            await user.click(
                await screen.findByRole('button', {
                    name: '행복주택 예비신혼부부 신청 확인서',
                }),
            );
            expect(screen.getByText(/대표신청자는.*변경이 불가/)).toBeVisible();
            expect(
                screen.getByRole('link', {
                    name: /공식 서식.*예비신혼부부 신청 확인서/,
                }),
            ).toHaveAttribute('href', expect.stringContaining('69385812'));
            expect(
                screen.getByRole('heading', {
                    name: '공식 확인이 필요한 서류 1개',
                }),
            ).toBeVisible();
        } finally {
            documents.unmount();
            documents.cache.clear();
        }
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

it('실제 공식 번호 절에서 자동 생성한 상황은 일회성 입력·결과에도 같은 분기를 쓰고 저장하지 않는다', async () => {
    useGeneratedSangok();
    const user = userEvent.setup();
    const evaluate = vi.spyOn(oneTimeCheckApi, 'evaluate');
    const save = vi.spyOn(api, 'answers');
    const start = vi.spyOn(api, 'start');
    const page = mount();
    try {
        await user.click(
            screen.getByRole('radio', { name: '신혼부부 · 혼인중인 자' }),
        );
        expect(
            screen.getByRole('complementary', { name: '질문 진행 단계' }),
        ).not.toHaveTextContent('예비신혼부부 신청 기준 ⑥');
        await user.click(screen.getByRole('button', { name: '이전' }));
        await user.click(
            screen.getByRole('radio', {
                name: '예비신혼부부 · 혼인을 계획 중이며 입주 전까지 혼인사실을 증명할 수 있을 것',
            }),
        );
        const steps = screen.getByRole('complementary', {
            name: '질문 진행 단계',
        });
        expect(steps).not.toHaveTextContent('신혼부부 신청 기준 ②');
        expect(steps).toHaveTextContent('예비신혼부부 신청 기준 ⑥');
        for (
            let remaining = generatedSangok.questions.length;
            remaining > 0;
            remaining--
        ) {
            if (evaluate.mock.calls.length) break;
            fireEvent.click(
                screen.getByRole('radio', { name: '잘 모르겠어요' }),
            );
        }
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        const result = await evaluate.mock.results.at(-1)!.value;
        expect(result.answers['applicant-situation']).toBe('PREMARITAL');
        expect(
            result.documents.find((d: { title: string }) =>
                d.title.includes('예비신혼부부 신청 확인서'),
            )?.necessity,
        ).toBe('CONDITIONAL');
        expect(
            result.documents.find(
                (d: { title: string }) => d.title === '가족관계증명서',
            )?.necessity,
        ).toBe('REQUIRED');
        const form = screen
            .getByRole('heading', { name: '행복주택 예비신혼부부 신청 확인서' })
            .closest('details')!;
        await user.click(
            within(form).getByText('행복주택 예비신혼부부 신청 확인서'),
        );
        expect(
            within(form).getByText(/대표신청자는.*변경이 불가/),
        ).toBeVisible();
        expect(
            within(form).getByRole('link', {
                name: /공식 서식.*예비신혼부부 신청 확인서/,
            }),
        ).toHaveAttribute('href', expect.stringContaining('69385812'));
        expect(save).not.toHaveBeenCalled();
        expect(start).not.toHaveBeenCalled();
    } finally {
        page.unmount();
        page.cache.clear();
    }
});

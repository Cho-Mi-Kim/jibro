import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OneTimeCheckSession } from './OneTimeCheckSession';
import { oneTimeCheckApi, type OneTimeCheckResult } from './oneTimeCheckApi';
import type { DocumentItem, Question } from '../housing/types';
import { notices } from '../housing/demo';
import * as scrolling from '../../shared/ui/animateScroll';

vi.mock('./oneTimeCheckApi', () => ({
    oneTimeCheckApi: { evaluate: vi.fn() },
}));
const notice = notices[0];
function result(): OneTimeCheckResult {
    return {
        noticeId: notice.id,
        title: notice.title,
        version: notice.version,
        questions: notice.questions,
        answers: { residence: 'YES', house: 'UNKNOWN' },
        documents: [],
        warnings: [],
    };
}
function mount(currentNotice = notice) {
    const cache = new QueryClient();
    const view = render(
        <QueryClientProvider client={cache}>
            <OneTimeCheckSession notice={currentNotice} />
        </QueryClientProvider>,
    );
    return { ...view, cache };
}
async function answerAll() {
    await userEvent.click(screen.getByRole('radio', { name: '확인했어요' }));
    await userEvent.click(screen.getByRole('radio', { name: '잘 모르겠어요' }));
}
beforeEach(() => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {});
    vi.stubGlobal(
        'ResizeObserver',
        class {
            observe = vi.fn();
            unobserve = vi.fn();
            disconnect = vi.fn();
        },
    );
    vi.mocked(oneTimeCheckApi.evaluate).mockReset().mockResolvedValue(result());
});
afterEach(() => vi.restoreAllMocks());

describe('저장하지 않는 조건 확인', () => {
    it('첫 복수선택 질문은 한 항목을 고른 뒤에도 머물고 추가 선택 후 다음으로 이동한다', async () => {
        const user = userEvent.setup();
        const questions: Question[] = [
            {
                ...notice.questions[0],
                id: 'family-members',
                title: '함께 신청할 가족을 선택하세요',
                inputKind: 'MULTIPLE',
                constraints: { maxSelections: 2 },
                options: [
                    { value: 'PARENT', label: '부모' },
                    { value: 'CHILD', label: '자녀' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
            notice.questions[1],
        ];
        render(<OneTimeCheckSession notice={{ ...notice, questions }} />);
        await user.click(screen.getByRole('checkbox', { name: '부모' }));
        expect(screen.getByLabelText('전체 2개 중 1번째 질문')).toBeVisible();
        await user.click(screen.getByRole('checkbox', { name: '자녀' }));
        expect(screen.getByRole('checkbox', { name: '부모' })).toBeChecked();
        expect(screen.getByRole('checkbox', { name: '자녀' })).toBeChecked();
        await user.click(screen.getByRole('button', { name: '다음' }));
        expect(screen.getByLabelText('전체 2개 중 2번째 질문')).toBeVisible();
        await user.click(screen.getByRole('button', { name: '이전' }));
        expect(screen.getByRole('checkbox', { name: '부모' })).toBeChecked();
        expect(screen.getByRole('checkbox', { name: '자녀' })).toBeChecked();
        expect(oneTimeCheckApi.evaluate).not.toHaveBeenCalled();
    });

    it('결과의 조건과 서류를 여닫을 때 각각 선택한 카드 위치로 스크롤한다', async () => {
        const scroll = vi
            .spyOn(scrolling, 'animateScroll')
            .mockImplementation(() => () => {});
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...result(),
            documents: [
                {
                    id: 'form',
                    title: '신청서',
                    necessity: 'REQUIRED',
                    state: 'UNCHECKED',
                    issuer: 'LH',
                    method: '양식 작성',
                    issueUrl: null,
                    note: '',
                    evidence: notice.questions[0].evidence,
                },
            ],
        });
        mount({
            ...notice,
            schedules: [
                {
                    id: 'confirmed-result',
                    title: '예비입주자 결과 발표',
                    date: '2027-03-25',
                    evidence: notice.evidence,
                },
            ],
        });
        await answerAll();
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', { name: '확인할 일정' }),
        ).toBeVisible();
        const condition = screen
            .getByText('내 답변: 확인했어요')
            .closest('li')!;
        const documentCard = screen
            .getByRole('heading', { name: '신청서' })
            .closest('section')!;
        for (const [card, top] of [
            [condition, 240],
            [documentCard, 590],
        ] as const) {
            vi.spyOn(card, 'getBoundingClientRect').mockReturnValue(
                new DOMRect(0, top, 320, 120),
            );
            for (const open of [true, false]) {
                scroll.mockClear();
                await userEvent.click(card);
                expect(card.querySelector('details')!.open).toBe(open);
                await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
                const [, position] = scroll.mock.lastCall!;
                expect(
                    typeof position === 'function' ? position() : position,
                ).toBe(top - 12);
            }
        }
    });
    it('결과에서 답변 완료와 추가 확인 항목 모두 요약하고 전체 설명과 출처를 연다', async () => {
        const reason =
            '분리 배우자도 포함됩니다. 직계존속·직계비속과 그 배우자는 신청자 또는 분리 배우자의 등본 등재 조건이 다릅니다.';
        const questions = [
            {
                ...notice.questions[0],
                title: '공고의 자격검증 대상 세대원을 모두 확인했나요?',
                reason,
                evidence: {
                    ...notice.questions[0].evidence,
                    url: 'https://apply.lh.or.kr/',
                },
            },
            {
                ...notice.questions[1],
                title: '면적 기준을 확인했나요?',
                reason: '주택 면적은 36.5㎡ 이하입니다.',
            },
        ];
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...result(),
            questions,
        });
        render(<OneTimeCheckSession notice={{ ...notice, questions }} />);
        await answerAll();
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(
            screen.getByRole('heading', { name: '누구까지 함께 심사하나요?' }),
        ).toBeVisible();
        expect(screen.getByText('내 답변: 확인했어요')).toBeVisible();
        expect(
            screen.getByText('배우자는 등본이 따로여도 함께 심사해요'),
        ).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '면적 기준' }),
        ).toBeVisible();
        expect(screen.getByText('내 답변: 잘 모르겠어요')).toBeVisible();
        expect(screen.getByText('주택 면적은 36.5㎡ 이하')).toBeVisible();
        const detail = screen.getByText(
            /누구의 등본에 올라 있는지에 따라 심사 대상이 달라져요/,
        );
        expect(detail).not.toBeVisible();
        await userEvent.click(
            screen.getByLabelText(
                '누구까지 함께 심사하나요? 전체 조건·공식 근거 보기',
            ),
        );
        expect(detail).toBeVisible();
        expect(screen.getByRole('link')).toHaveAttribute(
            'href',
            questions[0].evidence.url,
        );
        await userEvent.click(screen.getByRole('link'));
        expect(detail).toBeVisible();
        await userEvent.click(detail);
        expect(detail).not.toBeVisible();
        await userEvent.click(
            screen.getByText('내 답변: 확인했어요').closest('li')!,
        );
        expect(detail).toBeVisible();
        expect(
            screen
                .getByText('면적 기준')
                .closest('li')!
                .querySelector('details'),
        ).not.toHaveAttribute('open');
    });
    it('각 서류의 제출 대상과 발급 방법을 해당 공고의 선택지와 안내로 요약한다', async () => {
        const method =
            '공고일 이후 발급 기준을 확인하세요. 첨부 양식을 작성하세요. 자세한 작성 예시는 별지를 참고하세요.';
        const base: DocumentItem = {
            id: 'form',
            title: '공통 신청서',
            necessity: 'REQUIRED',
            method,
            issuer: 'LH 공고 첨부 양식',
            state: 'UNCHECKED',
            issueUrl: null,
            note: '',
            evidence: notice.questions[0].evidence,
        };
        const options = [
            { value: 'MARRIED', label: '신혼부부' },
            { value: 'PREMARITAL', label: '예비신혼부부' },
        ];
        const documents: DocumentItem[] = [
            base,
            {
                ...base,
                id: 'wedding',
                title: '혼인 계획 확인서',
                necessity: 'CONDITIONAL',
            },
            {
                ...base,
                id: 'pending',
                title: '추가 확인 서류',
                necessity: 'UNKNOWN',
            },
            {
                ...base,
                id: 'skip',
                title: '제외된 서류',
                necessity: 'NOT_APPLICABLE',
            },
        ];
        const questions = [
            { ...notice.questions[0], options },
            notice.questions[1],
        ];
        const documentTemplates = documents.map((doc) => ({
            ...doc,
            conditionQuestionId: doc.id === 'form' ? null : questions[0].id,
            conditionAnswerValues: ['PREMARITAL'],
        }));
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...result(),
            questions,
            documents,
            answers: { residence: 'PREMARITAL', house: 'UNKNOWN' },
        });
        render(
            <OneTimeCheckSession
                notice={{ ...notice, questions, documentTemplates }}
            />,
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '예비신혼부부' }),
        );
        await userEvent.click(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        );
        await screen.findByRole('heading', { name: '확인할 서류 3개' });
        expect(screen.getByText('모든 신청자')).not.toBeVisible();
        const commonTitle = screen.getByRole('heading', {
            name: '공통 신청서',
        });
        await userEvent.click(commonTitle);
        expect(screen.getByText('모든 신청자')).toBeVisible();
        await userEvent.click(commonTitle);
        const card = screen
            .getByRole('heading', { name: '혼인 계획 확인서' })
            .closest('section')!;
        const section = within(card);
        expect(section.getByText('제출 대상')).not.toBeVisible();
        expect(section.getByText('발급 방법')).not.toBeVisible();
        await userEvent.click(section.getByRole('heading'));
        expect(section.getByText('예비신혼부부')).toBeVisible();
        expect(section.queryByText('신혼부부')).toBeNull();
        expect(
            section.getByText('LH 공고 첨부 양식 · 첨부 양식을 작성해요.'),
        ).toBeVisible();
        const pending = screen.getByText('해당 여부 추가 확인 · 예비신혼부부');
        expect(pending).not.toBeVisible();
        await userEvent.click(
            screen.getByRole('heading', { name: '추가 확인 서류' }),
        );
        expect(pending).toBeVisible();
        expect(screen.queryByText('제외된 서류')).toBeNull();
        expect(
            section.getByText('자세한 작성 예시는 별지를 참고해요.'),
        ).toBeVisible();
        await userEvent.click(section.getByText('예비신혼부부'));
        expect(
            section.getByText('자세한 작성 예시는 별지를 참고해요.'),
        ).not.toBeVisible();
        expect(section.getByText('예비신혼부부')).not.toBeVisible();
        await userEvent.click(card);
        expect(
            section.getByText('자세한 작성 예시는 별지를 참고해요.'),
        ).toBeVisible();
        expect(commonTitle.closest('details')).not.toHaveAttribute('open');
    });
    it('답변 전에 현재 질문의 핵심 기준을 보여 주고 질문이 바뀌면 전체 설명을 접는다', async () => {
        const reason =
            '자동차 기준은 완화하지 않습니다. 비영업용 승용자동차별 가액 중 최고 가액이 5,451만원 이하인지 확인합니다. 배우자 등 다른 세대원의 차량도 포함합니다.';
        const questions = notice.questions.map((q, i) => ({
            ...q,
            title: i === 0 ? '세대 전체의 자동차 기준을 확인했나요?' : q.title,
            reason: i === 0 ? reason : '만 19세 이상입니다.',
        }));
        render(<OneTimeCheckSession notice={{ ...notice, questions }} />);
        const title = '세대 전체의 자동차 기준을 확인했나요?';
        const preview =
            '영업용이 아닌 승용차 중 가장 비싼 차가 5,451만 원 이하';
        const detail = /배우자 등 다른 세대원의 차도 함께 봐요/;
        expect(screen.getByRole('group', { name: title })).toBeVisible();
        expect(screen.getByTitle(title)).toBeInTheDocument();
        expect(screen.getByText(preview)).toBeVisible();
        expect(screen.getByText(detail)).not.toBeVisible();
        await userEvent.click(screen.getByText(preview));
        expect(screen.getByText(detail)).toBeVisible();
        await userEvent.click(
            screen.getByRole('group', { name: title }).closest('section')!,
        );
        expect(screen.getByText(detail)).not.toBeVisible();
        await userEvent.click(screen.getByText('답변을 고를 때 참고해요'));
        expect(screen.getByText(detail)).toBeVisible();
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        expect(screen.getByText('만 19세 이상')).toBeVisible();
        expect(screen.getByText('만 19세 이상이에요.')).not.toBeVisible();
        expect(screen.queryByText(detail)).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: '이전' }));
        expect(screen.getByText(preview)).toBeVisible();
        expect(screen.getByText(detail)).not.toBeVisible();
        expect(screen.getByRole('radio', { name: '확인했어요' })).toBeChecked();
        expect(oneTimeCheckApi.evaluate).not.toHaveBeenCalled();
    });
    it('마지막 질문이 공식 확인 대기여도 정상 답변 뒤 결과로 진행한다', async () => {
        const partial = {
            ...notice,
            questions: notice.questions.map((question, index) => ({
                ...question,
                reviewRequired: index === 1,
            })),
        };
        render(<OneTimeCheckSession notice={partial} />);
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(oneTimeCheckApi.evaluate).toHaveBeenCalledExactlyOnceWith(
            notice.id,
            notice.version,
            { residence: 'YES' },
        );
    });

    it('모든 질문이 공식 확인 대기이면 답변을 요구하지 않고 서류 안내 결과를 연다', async () => {
        const partial = {
            ...notice,
            questions: notice.questions.map((question) => ({
                ...question,
                reviewRequired: true,
            })),
        };
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...result(),
            questions: partial.questions,
            answers: {},
        });
        render(<OneTimeCheckSession notice={partial} />);
        expect(screen.queryByRole('radio')).not.toBeInTheDocument();
        await userEvent.click(
            screen.getByRole('button', { name: '결과 확인' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(oneTimeCheckApi.evaluate).toHaveBeenCalledExactlyOnceWith(
            notice.id,
            notice.version,
            {},
        );
        expect(screen.getByText('답변 완료 0개 · 추가 확인 2개')).toBeVisible();
    });
    it('공식 확인 대기 질문을 강제하지 않고 정상 질문만으로 결과를 확인한다', async () => {
        const partial = {
            ...notice,
            questions: notice.questions.map((question, index) => ({
                ...question,
                reviewRequired: index === 0,
            })),
        };
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...result(),
            questions: partial.questions,
            answers: { house: 'YES' },
        });
        render(<OneTimeCheckSession notice={partial} />);
        expect(
            screen
                .getAllByRole('group')
                .find((element) => element.tagName === 'FIELDSET'),
        ).toHaveAccessibleName('본인에게 적용되는 무주택 기준을 확인했나요?');
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(oneTimeCheckApi.evaluate).toHaveBeenCalledExactlyOnceWith(
            notice.id,
            notice.version,
            { house: 'YES' },
        );
        expect(
            screen.getByText('공식 확인이 필요한 조건입니다.'),
        ).toBeVisible();
        expect(screen.getByText('답변 완료 1개 · 추가 확인 1개')).toBeVisible();
    });
    it('브라우저 페이지 캐시에서 복원되어도 이전 결과를 지운다', async () => {
        mount();
        await answerAll();
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        const restored = new Event('pageshow');
        Object.defineProperty(restored, 'persisted', { value: true });
        act(() => window.dispatchEvent(restored));
        expect(
            screen
                .getAllByRole('group')
                .find((element) => element.tagName === 'FIELDSET'),
        ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
        expect(
            screen.getByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });
    it('마지막 답변에서만 평가하고 브라우저 저장소와 Query·Mutation 캐시에 개인 답변을 남기지 않는다', async () => {
        const { cache } = mount();
        const storage = vi.spyOn(Storage.prototype, 'setItem');
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        expect(oneTimeCheckApi.evaluate).not.toHaveBeenCalled();
        await userEvent.click(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        );
        expect(
            await screen.findByRole('heading', { name: '이번 조건 확인 결과' }),
        ).toBeVisible();
        expect(oneTimeCheckApi.evaluate).toHaveBeenCalledExactlyOnceWith(
            notice.id,
            notice.version,
            {
                residence: 'YES',
                house: 'UNKNOWN',
            },
        );
        expect(screen.getByText('답변 완료 1개 · 추가 확인 1개')).toBeVisible();
        expect(storage).not.toHaveBeenCalled();
        expect(cache.getQueryCache().getAll()).toEqual([]);
        expect(cache.getMutationCache().getAll()).toEqual([]);
    });

    it('실패하면 현재 답변으로 재시도하고 결과를 지우면 처음부터 다시 확인한다', async () => {
        vi.mocked(oneTimeCheckApi.evaluate).mockRejectedValueOnce(
            new Error('연결 실패'),
        );
        mount();
        await answerAll();
        expect(await screen.findByRole('alert')).toHaveTextContent('연결 실패');
        expect(
            screen.getByRole('radio', { name: '잘 모르겠어요' }),
        ).toBeChecked();
        await userEvent.click(
            screen.getByRole('button', { name: '결과 확인' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        await userEvent.click(
            screen.getByRole('button', { name: '답변과 결과 지우기' }),
        );
        expect(
            screen
                .getAllByRole('group')
                .find((element) => element.tagName === 'FIELDSET'),
        ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
        expect(
            screen.getByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });

    it('화면을 떠나 다시 열면 이전 답변과 결과를 복원하지 않는다', async () => {
        const first = mount();
        await answerAll();
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        first.unmount();
        mount();
        expect(
            screen
                .getAllByRole('group')
                .find((element) => element.tagName === 'FIELDSET'),
        ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?');
        expect(
            screen.getByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });

    it('페이지를 나간 뒤 늦게 도착한 응답도 답변이나 결과를 복원하지 않는다', async () => {
        let finish: (value: OneTimeCheckResult) => void = () => {};
        vi.mocked(oneTimeCheckApi.evaluate).mockImplementation(
            () =>
                new Promise((resolve) => {
                    finish = resolve;
                }),
        );
        mount();
        await answerAll();
        expect(screen.getByRole('button', { name: '확인 중…' })).toBeDisabled();
        act(() => {
            window.dispatchEvent(new Event('pagehide'));
        });
        await act(async () => {
            finish(result());
        });
        await waitFor(() =>
            expect(
                screen
                    .getAllByRole('group')
                    .find((element) => element.tagName === 'FIELDSET'),
            ).toHaveAccessibleName('공고 기준일의 거주 조건을 확인했나요?'),
        );
        expect(
            screen.getByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });
});

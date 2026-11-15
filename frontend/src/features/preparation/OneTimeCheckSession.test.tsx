import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OneTimeCheckSession } from './OneTimeCheckSession';
import { oneTimeCheckApi, type OneTimeCheckResult } from './oneTimeCheckApi';
import type { DocumentItem } from '../housing/types';
import { notices } from '../housing/demo';

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
function mount() {
    const cache = new QueryClient();
    const view = render(
        <QueryClientProvider client={cache}>
            <OneTimeCheckSession notice={notice} />
        </QueryClientProvider>,
    );
    return { ...view, cache };
}
async function answerAll() {
    await userEvent.click(screen.getByRole('radio', { name: '확인했어요' }));
    await userEvent.click(screen.getByRole('radio', { name: '잘 모르겠어요' }));
}
beforeEach(() => {
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
    it('결과에서 답변 완료와 추가 확인 항목 모두 요약하고 전체 설명과 출처를 연다', async () => {
        const reason =
            '공고일 기준 2년 이상입니다. 같은 세대 기준입니다. 예외는 공식 공고에서 확인합니다.';
        const questions = [
            {
                ...notice.questions[0],
                title: '거주 기간을 확인했나요?',
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
            screen.getByRole('heading', { name: '거주 기간' }),
        ).toBeVisible();
        expect(screen.getByText('내 답변: 확인했어요')).toBeVisible();
        expect(screen.getByText('공고일 기준 2년 이상')).toBeVisible();
        expect(
            screen.getByRole('heading', { name: '면적 기준' }),
        ).toBeVisible();
        expect(screen.getByText('내 답변: 잘 모르겠어요')).toBeVisible();
        expect(screen.getByText('주택 면적은 36.5㎡ 이하')).toBeVisible();
        expect(screen.getByText(reason)).not.toBeVisible();
        await userEvent.click(
            screen.getByLabelText(
                '거주 기간을 확인했나요? 전체 조건·공식 근거 보기',
            ),
        );
        expect(screen.getByText(reason)).toBeVisible();
        expect(screen.getByRole('link')).toHaveAttribute(
            'href',
            questions[0].evidence.url,
        );
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
        expect(screen.getByText('모든 신청자')).toBeVisible();
        const section = within(
            screen
                .getByRole('heading', { name: '혼인 계획 확인서' })
                .closest('section')!,
        );
        expect(section.getByText('예비신혼부부')).toBeVisible();
        expect(section.queryByText('신혼부부')).toBeNull();
        expect(
            section.getByText('LH 공고 첨부 양식 · 첨부 양식을 작성하세요.'),
        ).toBeVisible();
        expect(
            screen.getByText('해당 여부 추가 확인 · 예비신혼부부'),
        ).toBeVisible();
        expect(screen.queryByText('제외된 서류')).toBeNull();
        expect(section.getByText(method)).not.toBeVisible();
        await userEvent.click(section.getByText('상세 안내·공식 근거 보기'));
        expect(section.getByText(method)).toBeVisible();
    });
    it('답변 전에 현재 질문의 핵심 기준을 보여 주고 질문이 바뀌면 전체 설명을 접는다', async () => {
        const reason =
            '거주 기간은 2년 이상입니다. 주민등록을 기준으로 확인합니다. 예외는 공식 공고에서 확인합니다.';
        const questions = notice.questions.map((q, i) => ({
            ...q,
            reason: i === 0 ? reason : '만 19세 이상입니다.',
        }));
        render(<OneTimeCheckSession notice={{ ...notice, questions }} />);
        expect(screen.getByText('거주 기간은 2년 이상')).toBeVisible();
        expect(screen.getByText(reason)).not.toBeVisible();
        await userEvent.click(screen.getByText('전체 조건 보기'));
        expect(screen.getByText(reason)).toBeVisible();
        await userEvent.click(
            screen.getByRole('radio', { name: '확인했어요' }),
        );
        expect(screen.getByText('만 19세 이상')).toBeVisible();
        expect(screen.getByText('만 19세 이상입니다.')).not.toBeVisible();
        expect(screen.queryByText(reason)).not.toBeInTheDocument();
        await userEvent.click(screen.getByRole('button', { name: '이전' }));
        expect(screen.getByText('거주 기간은 2년 이상')).toBeVisible();
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
        expect(screen.getByRole('group')).toHaveAccessibleName(
            notice.questions[1].title,
        );
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
        expect(screen.getByRole('group')).toHaveAccessibleName(
            notice.questions[0].title,
        );
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
        expect(screen.getByRole('group')).toHaveAccessibleName(
            notice.questions[0].title,
        );
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
        expect(screen.getByRole('group')).toHaveAccessibleName(
            notice.questions[0].title,
        );
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
            expect(screen.getByRole('group')).toHaveAccessibleName(
                notice.questions[0].title,
            ),
        );
        expect(
            screen.getByRole('radio', { name: '확인했어요' }),
        ).not.toBeChecked();
        expect(
            screen.queryByRole('heading', { name: '이번 조건 확인 결과' }),
        ).not.toBeInTheDocument();
    });
});

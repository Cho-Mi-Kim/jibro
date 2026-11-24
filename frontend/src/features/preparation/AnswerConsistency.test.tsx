import { readFileSync } from 'node:fs';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, afterEach, describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import type {
    DocumentTemplate,
    Notice,
    Preparation,
    Question,
} from '../housing/types';
import { notices } from '../housing/demo';
import { OneTimeCheckSession } from './OneTimeCheckSession';
import { oneTimeCheckApi } from './oneTimeCheckApi';
import { ConditionResult } from './ConditionResult';
import { HistorySnapshot } from './history/HistorySnapshot';
import { getQuestionStep } from './questionNavigation';
import { QuestionPrompt } from './QuestionPrompt';
import * as scrolling from '../../shared/ui/animateScroll';

vi.mock('./oneTimeCheckApi', () => ({
    oneTimeCheckApi: { evaluate: vi.fn() },
}));
vi.mock('./PreparationUI', () => ({ NoticeContext: () => null }));
const contract = JSON.parse(
    readFileSync('../data/contracts/answer-dependencies.json', 'utf8'),
) as { questions: Question[]; documents: DocumentTemplate[] };
const message = contract.questions[1].answerDependencies![0].message;
const notice: Notice = {
    ...notices[0],
    questions: contract.questions,
    documentTemplates: contract.documents,
};
function record(): Preparation {
    return {
        id: 'p',
        noticeId: notice.id,
        title: '검토할 준비',
        version: 6,
        revision: 1,
        guideState: 'AVAILABLE',
        questions: structuredClone(contract.questions),
        answers: { category: 'YOUTH', 'applies-family': 'NONE' },
        documents: contract.documents.map((d) => ({
            ...d,
            necessity: 'CONDITIONAL',
            state: 'UNCHECKED',
            note: '보관한 준비 메모',
        })),
        schedules: [],
        progress: 15,
        houseStage: 1,
        warnings: [message],
        nextAction: { label: '', reason: '', path: '' },
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
    vi.mocked(oneTimeCheckApi.evaluate).mockReset().mockResolvedValue(record());
});
afterEach(() => vi.restoreAllMocks());

describe('서로 다른 답변의 재확인 안내', () => {
    it('새 모순은 질문 상단으로 안내하고 선택을 수정해도 입력 초점을 빼앗지 않는다', async () => {
        const scroll = vi
            .spyOn(scrolling, 'animateScroll')
            .mockImplementation(() => () => {});
        const question = contract.questions[1];
        function questionCard(value: string) {
            return (
                <div data-question-page>
                    <fieldset>
                        <QuestionPrompt
                            question={question}
                            answers={{
                                category: 'YOUTH',
                                'applies-family': value,
                            }}
                            questions={contract.questions}
                        />
                        <input aria-label="답변 선택" />
                    </fieldset>
                </div>
            );
        }
        const view = render(questionCard('REQUIRED_TYPE'));
        const input = screen.getByRole('textbox', { name: '답변 선택' });
        input.focus();
        expect(scroll).not.toHaveBeenCalled();
        view.rerender(questionCard('NONE'));
        await waitFor(() => expect(scroll).toHaveBeenCalledTimes(1));
        expect(scroll.mock.calls[0][0]).toBe(window);
        expect(screen.getByText(message)).toBeVisible();
        expect(input).toHaveFocus();
        view.rerender(questionCard('NONE'));
        view.rerender(questionCard('REQUIRED_TYPE'));
        expect(screen.queryByText(message)).toBeNull();
        expect(scroll).toHaveBeenCalledTimes(1);
        expect(input).toHaveFocus();
    });
    it('모순을 고르면 자동 이동을 멈추고 필수 서류를 유지한 결과와 수정 경로를 제공한다', async () => {
        render(<OneTimeCheckSession notice={notice} />);
        await userEvent.click(
            screen.getByRole('radio', {
                name: '청년 · 만 19~39세, 미혼',
            }),
        );
        await userEvent.click(
            screen.getByRole('radio', {
                name: '위 상황에 해당하지 않아요',
            }),
        );
        expect(screen.getByText(message)).toBeVisible();
        expect(
            screen.getByRole('radio', {
                name: '위 상황에 해당하지 않아요',
            }),
        ).toBeChecked();
        expect(oneTimeCheckApi.evaluate).not.toHaveBeenCalled();
        await userEvent.click(
            screen.getByRole('button', { name: '결과 확인' }),
        );
        await screen.findByRole('heading', { name: '이번 조건 확인 결과' });
        expect(oneTimeCheckApi.evaluate).toHaveBeenCalledWith(
            notice.id,
            notice.version,
            { category: 'YOUTH', 'applies-family': 'NONE' },
        );
        expect(screen.getByText('답변 완료 1개 · 추가 확인 1개')).toBeVisible();
        expect(screen.getByText(message)).toBeVisible();
        expect(
            screen.getByRole('heading', {
                name: '가족관계증명서',
            }),
        ).toBeVisible();
        await userEvent.click(
            screen.getByRole('button', { name: '답변 수정' }),
        );
        expect(
            screen.getByRole('group', {
                name: '배우자·가족 관계에서 내 상황은 무엇인가요?',
            }),
        ).toBeVisible();
        vi.mocked(oneTimeCheckApi.evaluate).mockResolvedValue({
            ...record(),
            answers: { category: 'YOUTH', 'applies-family': 'REQUIRED_TYPE' },
            warnings: [],
        });
        await userEvent.click(
            screen.getByRole('radio', {
                name: '청년·사회초년생·한부모·예비신혼부부로 신청해요',
            }),
        );
        await screen.findByText('답변 완료 2개 · 추가 확인 0개');
        expect(screen.queryByText(message)).toBeNull();
        expect(
            screen.getByRole('heading', {
                name: '가족관계증명서',
            }),
        ).toBeVisible();
    });

    it('내 준비의 완료 개수와 재확인 질문 위치도 같은 모순을 가리킨다', () => {
        const p = record();
        expect(getQuestionStep(p)).toBe(1);
        expect(
            getQuestionStep(
                { questions: [p.questions[1]], answers: p.answers },
                undefined,
                p.questions,
            ),
        ).toBe(0);
        render(
            <MemoryRouter>
                <ConditionResult p={p} inTab={false} />
            </MemoryRouter>,
        );
        const card = screen
            .getByRole('heading', { name: '가족관계증명서는 누가 내나요?' })
            .closest('article')!;
        expect(within(card).getByText('추가 확인 필요')).toBeVisible();
        expect(within(card).getByText(message)).toBeVisible();
        expect(
            within(card).getByRole('link', { name: /답변 이어서 작성/ }),
        ).toHaveAttribute(
            'href',
            '/preparations/p/conditions/edit?question=applies-family',
        );
    });

    it('이전 기록은 당시 질문의 규칙만 사용하며 구버전에 새 모순을 소급하지 않는다', () => {
        const p = record();
        const view = render(<HistorySnapshot preparation={p} />);
        expect(screen.getByText(message)).toBeVisible();
        expect(screen.getByText('보관한 준비 메모')).toBeInTheDocument();
        view.rerender(
            <HistorySnapshot
                preparation={{
                    ...p,
                    questions: p.questions.map((q) => ({
                        ...q,
                        answerDependencies: undefined,
                    })),
                }}
            />,
        );
        expect(screen.queryByText(message)).toBeNull();
        expect(screen.queryByText('추가 확인 필요')).toBeNull();
        expect(screen.getAllByText('답변 완료')).toHaveLength(2);
    });
});

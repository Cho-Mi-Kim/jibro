import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
    answerConflictMessages,
    dependentDocumentNecessity,
} from './answerDependencies';
import {
    answerDemoPreparation,
    assessDemoPreparation,
    demoDocumentNecessity,
} from './demoPreparationRules';
import { needsFollowUp } from './questions';
import type { Answer, DocumentTemplate, Preparation, Question } from './types';

const contract = JSON.parse(
    readFileSync('../data/contracts/answer-dependencies.json', 'utf8'),
) as {
    questions: Question[];
    documents: DocumentTemplate[];
    cases: {
        name: string;
        answers: Record<string, Answer>;
        followUpQuestionIds: string[];
        conflictQuestionIds: string[];
        necessity: Record<string, string>;
    }[];
};
const rules = Object.fromEntries(
    contract.documents.map((d) => [
        d.id,
        {
            questionId: d.conditionQuestionId!,
            answerValues: d.conditionAnswerValues!,
        },
    ]),
);
function preparation(): Preparation {
    return {
        id: 'p',
        noticeId: 'n',
        title: '준비',
        version: 6,
        revision: 1,
        guideState: 'AVAILABLE',
        questions: structuredClone(contract.questions),
        answers: {},
        documents: contract.documents.map((d) => ({
            ...d,
            necessity: 'UNKNOWN',
            state: 'READY',
            note: '보존할 메모',
        })),
        schedules: [],
        progress: 0,
        houseStage: 1,
        warnings: [],
        nextAction: { label: '', reason: '', path: '' },
    };
}

describe('서버와 시연의 답변 의존 조건 공통 계약', () => {
    it.each(contract.cases)('$name', (entry) => {
        const before = preparation();
        const original = structuredClone(before);
        const result = answerDemoPreparation(before, entry.answers, rules);
        expect(result.answers).toEqual(entry.answers);
        expect(before).toEqual(original);
        expect(
            result.questions
                .filter((q) =>
                    needsFollowUp(
                        q,
                        result.answers[q.id],
                        result.answers,
                        result.questions,
                    ),
                )
                .map((q) => q.id),
        ).toEqual(entry.followUpQuestionIds);
        expect(
            result.questions
                .filter(
                    (q) =>
                        answerConflictMessages(
                            q,
                            result.answers[q.id],
                            result.answers,
                            result.questions,
                        ).length,
                )
                .map((q) => q.id),
        ).toEqual(entry.conflictQuestionIds);
        expect(
            Object.fromEntries(
                result.documents.map((d) => [d.id, d.necessity]),
            ),
        ).toEqual(entry.necessity);
        expect(result.documents.every((d) => d.note === '보존할 메모')).toBe(
            true,
        );
    });

    it('모순은 완료율에 포함하지 않고 이유와 답변 재확인 경로를 유지한다', () => {
        const result = assessDemoPreparation(
            answerDemoPreparation(
                preparation(),
                { category: 'YOUTH', 'applies-family': 'NONE' },
                rules,
            ),
        );
        const family = result.questions[1];
        expect(result.warnings).toEqual([
            family.title,
            family.answerDependencies![0].message,
        ]);
        expect(result.progress).toBeLessThan(100);
        // A formerly ready requirement is rechecked before the answer task.
        expect(result.documents[0].state).toBe('RECHECK');
        const unchecked = {
            ...result,
            documents: result.documents.map((d) => ({
                ...d,
                state: 'UNCHECKED' as const,
            })),
        };
        expect(assessDemoPreparation(unchecked).nextAction).toEqual({
            label: '확인하지 못한 조건 살펴보기',
            reason: '서로 다른 답변을 다시 확인해 주세요.',
            path: '/preparations/p/conditions/edit',
        });
    });

    it('유형을 바꿔 새 모순이 생겨도 필수 서류와 메모를 보존하고 해소하면 경고만 없앤다', () => {
        const old = answerDemoPreparation(
            preparation(),
            { category: 'ELDER', 'applies-family': 'NONE' },
            rules,
        );
        const conflicting = answerDemoPreparation(
            old,
            { ...old.answers, category: 'YOUTH' },
            rules,
        );
        expect(conflicting.documents[0].necessity).toBe('CONDITIONAL');
        const fixed = assessDemoPreparation(
            answerDemoPreparation(
                conflicting,
                { ...conflicting.answers, 'applies-family': 'REQUIRED_TYPE' },
                rules,
            ),
        );
        expect(fixed.warnings).toEqual([]);
        expect(fixed.documents[0].necessity).toBe('CONDITIONAL');
        expect(fixed.documents[0].note).toBe('보존할 메모');
    });

    it('공식 검수 필요는 공통 필수보다 우선하며 확정된 다른 근거는 미확인 선택으로 제외하지 않는다', () => {
        const answers = { category: 'YOUTH', 'applies-family': 'NONE' };
        expect(
            dependentDocumentNecessity(
                'REQUIRED',
                'family',
                contract.questions,
                answers,
            ),
        ).toBe('REQUIRED');
        const held = contract.questions.map((q) =>
            q.id === 'category' ? { ...q, reviewRequired: true } : q,
        );
        expect(
            dependentDocumentNecessity('REQUIRED', 'family', held, answers),
        ).toBe('UNKNOWN');
        expect(answerConflictMessages(held[1], 'NONE', answers, held)).toEqual(
            [],
        );
        expect(
            dependentDocumentNecessity(
                'CONDITIONAL',
                'family',
                contract.questions,
                { category: 'UNKNOWN', 'applies-family': 'NO_SPOUSE' },
            ),
        ).toBe('CONDITIONAL');
    });

    it('구버전 질문에는 새 규칙을 소급하지 않는다', () => {
        const old = preparation();
        old.questions = old.questions.map((q) => ({
            ...q,
            answerDependencies: undefined,
        }));
        const answers = { category: 'YOUTH', 'applies-family': 'NONE' };
        const result = answerDemoPreparation(old, answers, rules);
        expect(result.documents[0].necessity).toBe('NOT_APPLICABLE');
        expect(
            needsFollowUp(
                result.questions[1],
                'NONE',
                answers,
                result.questions,
            ),
        ).toBe(false);
    });

    it('복수 선택과 경고 전용 조건도 같은 방식으로 평가한다', () => {
        const parent: Question = {
            ...contract.questions[0],
            inputKind: 'MULTIPLE',
            options: ['A', 'B', 'UNKNOWN'].map((value) => ({
                value,
                label: value,
            })),
        };
        const question: Question = {
            ...contract.questions[1],
            inputKind: 'MULTIPLE',
            options: ['X', 'Y', 'UNKNOWN'].map((value) => ({
                value,
                label: value,
            })),
            answerDependencies: [
                {
                    questionId: 'category',
                    answerValues: ['A'],
                    conflictingValues: ['X'],
                    requiredDocumentIds: [],
                    message: '다시 확인해요.',
                },
            ],
        };
        expect(
            answerConflictMessages(question, ['X'], { category: ['B', 'A'] }, [
                parent,
                question,
            ]),
        ).toEqual(['다시 확인해요.']);
        expect(
            dependentDocumentNecessity('NOT_APPLICABLE', 'family', [question], {
                category: ['A'],
            }),
        ).toBe('NOT_APPLICABLE');
        expect(
            demoDocumentNecessity(
                rules.family,
                { 'applies-family': 'NONE' },
                contract.questions,
            ),
        ).toBe('NOT_APPLICABLE');
    });

    it.each([42, 'UNRECOGNIZED', [], ['YOUTH']])(
        '잘못 저장된 부모 답변 %j는 서류를 제외하는 근거로 사용하지 않는다',
        (category) => {
            const answers = { category, 'applies-family': 'NONE' };
            expect(
                dependentDocumentNecessity(
                    'NOT_APPLICABLE',
                    'family',
                    contract.questions,
                    answers,
                ),
            ).toBe('UNKNOWN');
            expect(
                answerConflictMessages(
                    contract.questions[1],
                    'NONE',
                    answers,
                    contract.questions,
                ),
            ).toEqual([]);
        },
    );

    it.each([42, 'UNRECOGNIZED', [], ['NONE']])(
        '잘못 저장된 현재 답변 %j에는 잘못된 모순 설명을 표시하지 않는다',
        (answer) => {
            const answers = { category: 'YOUTH', 'applies-family': answer };
            expect(
                answerConflictMessages(
                    contract.questions[1],
                    answer,
                    answers,
                    contract.questions,
                ),
            ).toEqual([]);
            expect(
                needsFollowUp(
                    contract.questions[1],
                    answer,
                    answers,
                    contract.questions,
                ),
            ).toBe(true);
            expect(
                dependentDocumentNecessity(
                    'NOT_APPLICABLE',
                    'family',
                    contract.questions,
                    answers,
                ),
            ).toBe('CONDITIONAL');
        },
    );

    it('부모 질문이 없는 불완전한 기록에서는 모순을 확정하지 않고 서류를 확인 대상으로 남긴다', () => {
        const answers = { category: 'YOUTH', 'applies-family': 'NONE' };
        const question = contract.questions[1];
        expect(answerConflictMessages(question, 'NONE', answers)).toEqual([]);
        expect(
            dependentDocumentNecessity(
                'NOT_APPLICABLE',
                'family',
                [question],
                answers,
            ),
        ).toBe('UNKNOWN');
    });
});

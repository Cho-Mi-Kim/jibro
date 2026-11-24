import { describe, expect, it } from 'vitest';
import {
    answerLabel,
    needsFollowUp,
    validAnswer,
    validSubmittedAnswer,
    answerableQuestions,
} from './questions';
import {
    answerDemoPreparation,
    demoDocumentNecessity,
} from './demoPreparationRules';
import type { Question, Preparation } from './types';

const base: Question = {
    id: 'test',
    title: '입력 테스트',
    reason: '검증용',
    evidence: { label: '테스트', url: null, checkedAt: '2026-11-06' },
};
const numeric: Question = {
    ...base,
    inputKind: 'NUMBER',
    constraints: { min: 0, max: 10, step: 0.25, unit: '개' },
};
const date: Question = {
    ...base,
    inputKind: 'DATE',
    constraints: { minDate: '2024-01-01', maxDate: '2024-12-31' },
};
const multiple: Question = {
    ...base,
    inputKind: 'MULTIPLE',
    options: [
        { value: 'A', label: '첫째' },
        { value: 'B', label: '둘째', requiresFollowUp: true },
        { value: 'C', label: '셋째' },
        { value: 'UNKNOWN', label: '모름' },
    ],
    constraints: { maxSelections: 2 },
};

describe('질문별 입력 계약', () => {
    it('공식 확인이 필요한 항목은 저장 답변이 있어도 확정하지 않고 질문 순서에서 제외한다', () => {
        const held = { ...base, id: 'official-review', reviewRequired: true };
        expect(needsFollowUp(held, 'YES')).toBe(true);
        expect(answerLabel(held, 'YES')).toBe('확인했어요');
        expect(validAnswer(held, 'YES')).toBe(true);
        expect(answerableQuestions([held, base])).toEqual([base]);
        expect(needsFollowUp(base, 'YES')).toBe(false);
    });
    it('기존 단일 선택 문자열과 미입력/모름 구분을 유지한다', () => {
        expect(validAnswer(base, 'YES')).toBe(true);
        expect(validAnswer(base, ['YES'])).toBe(false);
        expect(needsFollowUp(base, 'UNKNOWN')).toBe(true);
        expect(needsFollowUp(base)).toBe(true);
        expect(answerLabel(base, 'YES')).toBe('확인했어요');
    });
    it('0을 유효하게 저장하고 범위/정밀도/입력 단위를 서버와 동일하게 검증한다', () => {
        for (const value of [0, 0.25, 1.5, 10, 'UNKNOWN'])
            expect(validAnswer(numeric, value)).toBe(true);
        for (const value of [
            -1,
            10.25,
            0.1,
            0.0000001,
            NaN,
            Infinity,
            '1',
            '',
            null,
            [],
        ])
            expect(validAnswer(numeric, value)).toBe(false);
        expect(
            validAnswer({ ...numeric, constraints: { step: 0.1 } }, 0.3),
        ).toBe(true);
        expect(
            validAnswer(
                { ...numeric, constraints: { step: 0.000001 } },
                0.000001,
            ),
        ).toBe(true);
        expect(needsFollowUp(numeric, 0)).toBe(false);
        expect(answerLabel(numeric, 1.25)).toBe('1.25 개');
    });
    it('실재하는 날짜와 범위를 검증하고 주민번호/생년월일을 자동 추가하지 않는다', () => {
        expect(validAnswer(date, '2024-02-29')).toBe(true);
        for (const value of [
            '2024-02-30',
            '2023-02-29',
            '2025-01-01',
            '2024-1-1',
            '0000-01-01',
            20240101,
        ])
            expect(validAnswer(date, value)).toBe(false);
        expect(validAnswer(date, 'UNKNOWN')).toBe(true);
        expect(answerLabel(date, '2024-02-29')).toBe('2024-02-29');
    });
    it('다중 선택은 모름과 혼합하거나 중복할 수 없고 하나라도 추가 확인인 경우 안내한다', () => {
        expect(validAnswer(multiple, ['B', 'A'])).toBe(true);
        for (const value of [
            [],
            ['A', 'A'],
            ['A', 'UNKNOWN'],
            ['UNKNOWN'],
            ['A', 'B', 'C'],
            ['OTHER'],
            'A',
        ])
            expect(validAnswer(multiple, value)).toBe(false);
        expect(validAnswer(multiple, 'UNKNOWN')).toBe(true);
        expect(needsFollowUp(multiple, ['A', 'B'])).toBe(true);
        expect(needsFollowUp(multiple, ['A'])).toBe(false);
        expect(answerLabel(multiple, ['A', 'B'])).toBe('첫째, 둘째');
        expect(
            demoDocumentNecessity(
                { questionId: 'test', answerValues: ['B'] },
                { test: ['A', 'B'] },
                [multiple],
            ),
        ).toBe('CONDITIONAL');
        expect(
            demoDocumentNecessity(
                { questionId: 'test', answerValues: ['B'] },
                { test: 'UNKNOWN' },
                [multiple],
            ),
        ).toBe('UNKNOWN');
    });
    it('데모도 다중 선택을 질문 순서로 저장하고 잘못된 입력으로 원본을 바꾸지 않는다', () => {
        const preparation = {
            questions: [multiple],
            answers: { test: ['A'] },
            documents: [],
        } as unknown as Preparation;
        expect(
            answerDemoPreparation(preparation, { test: ['B', 'A'] }, {}).answers
                .test,
        ).toEqual(['A', 'B']);
        expect(() =>
            answerDemoPreparation(preparation, { test: ['A', 'UNKNOWN'] }, {}),
        ).toThrow();
        expect(preparation.answers.test).toEqual(['A']);
    });
    it('해당 없음은 새 입력에서 단독 선택하며 기존 답변의 표시와 평가는 유지한다', () => {
        const question: Question = {
            ...multiple,
            options: [
                ...multiple.options!,
                { value: 'NONE', label: '해당 없음' },
            ],
        };
        expect(validSubmittedAnswer(question, ['NONE'])).toBe(true);
        expect(validSubmittedAnswer(question, ['A'])).toBe(true);
        expect(validSubmittedAnswer(question, 'UNKNOWN')).toBe(true);
        expect(validSubmittedAnswer(question, ['A', 'NONE'])).toBe(false);
        expect(validSubmittedAnswer(question, ['NONE', 'A'])).toBe(false);
        expect(validAnswer(question, ['A', 'NONE'])).toBe(true);
        expect(needsFollowUp(question, ['A', 'NONE'])).toBe(false);
        expect(answerLabel(question, ['A', 'NONE'])).toBe('첫째, 해당 없음');
        expect(
            validSubmittedAnswer({ ...question, inputKind: 'SINGLE' }, 'NONE'),
        ).toBe(true);
    });
    it('다른 답변 저장 시 기존 혼합 응답은 보존하고 새 혼합 응답은 거절한다', () => {
        const question: Question = {
            ...multiple,
            options: [
                ...multiple.options!,
                { value: 'NONE', label: '해당 없음' },
            ],
        };
        const preparation = {
            questions: [question, { ...base, id: 'other' }],
            answers: { test: ['A'] },
            documents: [],
        } as unknown as Preparation;
        expect(() =>
            answerDemoPreparation(preparation, { test: ['A', 'NONE'] }, {}),
        ).toThrow();
        preparation.answers.test = ['A', 'NONE'];
        const saved = answerDemoPreparation(
            preparation,
            { test: ['A', 'NONE'], other: 'YES' },
            {},
        );
        expect(saved.answers).toEqual({ test: ['A', 'NONE'], other: 'YES' });
        expect(preparation.answers).toEqual({ test: ['A', 'NONE'] });
        expect(
            answerDemoPreparation(preparation, { test: ['NONE'] }, {}).answers
                .test,
        ).toEqual(['NONE']);
    });
});

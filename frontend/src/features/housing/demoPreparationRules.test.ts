/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import {
    answerDemoPreparation,
    assessDemoPreparation,
} from './demoPreparationRules';
import type { Preparation } from './types';

const cases = JSON.parse(
    readFileSync('../data/contracts/preparation-rules.json', 'utf8'),
) as {
    name: string;
    preparation: Preparation;
    expected: Pick<
        Preparation,
        'progress' | 'houseStage' | 'warnings' | 'nextAction'
    >;
}[];

describe('실제 서버와 시연 준비 정책의 공통 계약', () => {
    it('유형 재확인 답변은 서류를 남기고 확정 비해당만 제외하며 기존 메모와 공통 서류를 보존한다', () => {
        const preparation = structuredClone(cases[0].preparation);
        preparation.questions = [
            {
                id: 'category',
                title: '신청 상황',
                reason: '',
                evidence: { label: '원문', url: null, checkedAt: '2026-11-22' },
                options: [
                    { value: 'STUDENT', label: '대학생' },
                    {
                        value: 'OTHER',
                        label: '유형 재확인 필요',
                        requiresFollowUp: true,
                    },
                    { value: 'NONE', label: '확정된 비해당' },
                    { value: 'UNKNOWN', label: '모름', requiresFollowUp: true },
                ],
            },
        ];
        preparation.answers = {};
        preparation.documents = [
            {
                ...preparation.documents[0],
                id: 'student',
                necessity: 'CONDITIONAL',
                state: 'READY',
                note: '이미 준비한 원본 보관',
            },
            {
                ...preparation.documents[0],
                id: 'common',
                necessity: 'REQUIRED',
                state: 'READY',
            },
        ];
        const rules = {
            student: { questionId: 'category', answerValues: ['STUDENT'] },
        };
        const before = structuredClone(preparation);
        for (const [answer, expected] of Object.entries({
            OTHER: 'UNKNOWN',
            NONE: 'NOT_APPLICABLE',
            STUDENT: 'CONDITIONAL',
            UNKNOWN: 'UNKNOWN',
        })) {
            const result = answerDemoPreparation(
                preparation,
                { category: answer },
                rules,
            );
            expect(result.documents[0]).toMatchObject({
                necessity: expected,
                note: '이미 준비한 원본 보관',
                state: expected === 'CONDITIONAL' ? 'READY' : 'RECHECK',
            });
            expect(result.documents[1]).toEqual(preparation.documents[1]);
        }
        expect(preparation).toEqual(before);
        preparation.questions[0].inputKind = 'MULTIPLE';
        expect(
            answerDemoPreparation(preparation, { category: ['OTHER'] }, rules)
                .documents[0].necessity,
        ).toBe('UNKNOWN');
        expect(
            answerDemoPreparation(
                preparation,
                { category: ['STUDENT', 'OTHER'] },
                rules,
            ).documents[0].necessity,
        ).toBe('CONDITIONAL');
    });

    it.each(cases)('$name', ({ preparation, expected }) => {
        const before = structuredClone(preparation);
        const result = assessDemoPreparation(preparation);
        expect({
            progress: result.progress,
            houseStage: result.houseStage,
            warnings: result.warnings,
            nextAction: result.nextAction,
        }).toEqual(expected);
        expect(preparation).toEqual(before);
    });

    it('미완료 답변이 있으면 반올림으로 100%가 되지 않는다', () => {
        const complete = structuredClone(
            cases.find((c) => c.name === 'complete-with-excluded-document')!
                .preparation,
        );
        complete.questions = Array.from({ length: 100 }, (_, i) => ({
            ...complete.questions[0],
            id: 'q' + i,
        }));
        complete.answers = Object.fromEntries(
            complete.questions.map((q, i) => [
                q.id,
                i === 99 ? 'UNKNOWN' : 'YES',
            ]),
        );
        expect(assessDemoPreparation(complete).progress).toBe(99);
        complete.answers.q99 = 'YES';
        expect(assessDemoPreparation(complete).progress).toBe(100);
    });

    it('일정 확인 대기만 남아도 완료율 100%로 확정하지 않는다', () => {
        const preparation = structuredClone(
            cases.find((c) => c.name === 'complete-with-excluded-document')!
                .preparation,
        );
        preparation.reviewIssues = [
            {
                id: 'time',
                area: 'SCHEDULE',
                title: '납부 시각',
                detail: '공식 자료의 시각 확인 필요',
                questionIds: [],
                documentIds: [],
                evidence: [],
            },
        ];
        const assessed = assessDemoPreparation(preparation);
        expect(assessed.progress).toBe(99);
        expect(assessed.nextAction.path).toBe(
            '/notices/' + preparation.noticeId,
        );
    });
});

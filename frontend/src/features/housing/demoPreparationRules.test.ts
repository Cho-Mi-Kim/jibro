/// <reference types="node" />
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { assessDemoPreparation } from './demoPreparationRules';
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

import { expect, it } from 'vitest';
import contracts from './reviewedConditions/specialized-contracts.json';
import type { Question } from './types';
import { osanConditionDetail } from './osanConditionDetail';
import { gyeongjuConditionDetail } from './gyeongjuConditionDetail';
import { samryeConditionDetail } from './samryeConditionDetail';
import { pohangConditionDetail } from './pohangConditionDetail';
import { conditionHeading } from './conditionOverview';

it.each([
    ['osan', '어떤 가족을 함께 심사하나요?'],
    ['gyeongju', '성년·무주택세대구성원 기준'],
    ['samrye', '누구의 집을 확인하고, 어떤 집은 허용하나요?'],
] as const)(
    'keeps %s household guidance out of other notices',
    (notice, heading) => {
        const reviewed = contracts.find(
            (entry) =>
                entry.notice === notice && entry.questionId === 'household',
        )!;
        const question = {
            ...reviewed.answerContract,
            id: reviewed.questionId,
            title: reviewed.sourceTitle,
            reason: reviewed.sourceReason,
            evidence: {
                url: reviewed.sourceEvidence.url,
                label: '공식 원문',
                checkedAt: '2026-11-22',
            },
        } as Question;
        const builders = {
            osan: osanConditionDetail,
            gyeongju: gyeongjuConditionDetail,
            samrye: samryeConditionDetail,
            pohang: pohangConditionDetail,
        };
        for (const [owner, render] of Object.entries(builders)) {
            if (owner !== notice) expect(render(question)).toBeUndefined();
        }
        expect(conditionHeading(question)).toBe(heading);
    },
);

it.each([
    ['69203687', 'income', osanConditionDetail],
    ['69355347', 'income', gyeongjuConditionDetail],
    ['68998510', 'vehicle', samryeConditionDetail],
    ['69143584', 'household', pohangConditionDetail],
] as const)(
    'keeps %s details only while every reviewed input contract matches',
    (file, id, render) => {
        const reviewed = contracts.find(
            (entry) =>
                entry.questionId === id &&
                entry.sourceEvidence.url.includes(file),
        )!;
        const question = {
            ...reviewed.answerContract,
            id,
            title: reviewed.sourceTitle,
            reason: reviewed.sourceReason,
            evidence: {
                url: reviewed.sourceEvidence.url,
                label: '공식 원문',
                checkedAt: '2026-11-22',
            },
        } as Question;
        expect(render(question)).toBeDefined();
        for (const change of [
            { title: '정정된 신청 대상' },
            { reviewRequired: true },
            { inputKind: 'NUMBER' as const },
            {
                options: [
                    {
                        value: 'DIFFERENT',
                        label: '다른 대상',
                        requiresFollowUp: true,
                    },
                ],
            },
            { constraints: { min: 25 } },
            {
                answerDependencies: [
                    {
                        questionId: 'changed',
                        answerValues: ['A'],
                        conflictingValues: ['B'],
                        requiredDocumentIds: ['new-document'],
                        message: '새 서류',
                    },
                ],
            },
            {
                applicability: {
                    questionId: 'type',
                    answerValues: ['NEW_TYPE'],
                },
            },
            {
                evidence: {
                    ...question.evidence,
                    url: question.evidence.url!.split('#')[0] + '#page=99',
                },
            },
        ])
            expect(
                render({ ...question, ...change } as Question),
            ).toBeUndefined();
    },
);

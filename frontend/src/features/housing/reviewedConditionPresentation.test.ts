import { matchesReviewedCondition } from './reviewedConditionIdentity';
import { describe, expect, it } from 'vitest';
import type { Question } from './types';
import {
    type ReviewedConditionPresentation,
    withReviewedConditions,
} from './reviewedConditionPresentation';
import duamCatalog from './reviewedConditions/notice-0000e667-e82e-4db9-b3cf-947ba42146f8.json';
import damyangCatalog from './reviewedConditions/notice-a4a8499e-432d-4e0b-91b7-b121a91a2818.json';

const question: Question = {
    id: 'household',
    title: '심사 대상 가족',
    reason: '신청자와 배우자는 주소가 달라도 함께 확인해요.',
    evidence: {
        label: '공고문 4쪽',
        url: 'https://apply.lh.or.kr/lhapply/lhFile.do?fileid=123#page=4',
        checkedAt: '2026-11-21',
    },
};
const entry: ReviewedConditionPresentation = {
    noticeId: 'example',
    questionId: question.id,
    sourceTitle: question.title,
    sourceReason: question.reason,
    sourceEvidence: { url: question.evidence.url!, sha256: 'a'.repeat(64) },
    heading: '어떤 가족을 함께 확인하나요?',
    preview: '따로 사는 배우자도 포함해요.',
    sections: [{ text: question.reason, evidence: [question.evidence] }],
};

describe('reviewed condition identity', () => {
    it('accepts only the reviewed original and its exact locator', () => {
        expect(matchesReviewedCondition(question, entry)).toBe(true);
        for (const change of [
            { id: 'other' },
            { title: '다른 기준' },
            { reason: '기준이 변경됐어요.' },
            { reviewRequired: true },
        ]) {
            expect(
                matchesReviewedCondition({ ...question, ...change }, entry),
            ).toBe(false);
        }
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    evidence: {
                        ...question.evidence,
                        url: question.evidence.url!.replace('page=4', 'page=5'),
                    },
                },
                entry,
            ),
        ).toBe(false);
    });
    it('rejects another official attachment or an explicit changed file digest', () => {
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    evidence: {
                        ...question.evidence,
                        url: question.evidence.url!.replace(
                            'fileid=123',
                            'fileid=124',
                        ),
                    },
                },
                entry,
            ),
        ).toBe(false);
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    evidence: {
                        ...question.evidence,
                        url:
                            question.evidence.url + '&sha256=' + 'b'.repeat(64),
                    },
                },
                entry,
            ),
        ).toBe(false);
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    evidence: {
                        ...question.evidence,
                        url:
                            question.evidence.url + '&sha256=' + 'a'.repeat(64),
                    },
                },
                entry,
            ),
        ).toBe(true);
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    evidence: {
                        ...question.evidence,
                        url:
                            question.evidence.url + '&sha256=' + 'a'.repeat(64),
                    },
                },
                { ...entry, sourceEvidence: { url: entry.sourceEvidence.url } },
            ),
        ).toBe(false);
    });
    it('accepts an explicitly reviewed wording revision, not an arbitrary one', () => {
        const updated = {
            ...entry,
            acceptedTitles: ['함께 심사하는 가족'],
            acceptedReasons: ['주소가 다른 배우자도 심사해요.'],
        };
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    title: updated.acceptedTitles[0],
                    reason: updated.acceptedReasons[0],
                },
                updated,
            ),
        ).toBe(true);
        expect(
            matchesReviewedCondition(
                { ...question, reason: '같이 사는 가족만 심사해요.' },
                updated,
            ),
        ).toBe(false);
    });
    it('leaves unknown and older originals intact without importing unrelated catalogs', async () => {
        const data = { questions: [question] };
        expect(await withReviewedConditions(data)).toBe(data);
        expect(await withReviewedConditions({})).toEqual({});
        expect(data.questions[0]).toBe(question);
    });

    it('checks only explicitly reviewed answer contract fields', () => {
        const updated = { ...entry, answerContract: { options: null } };
        expect(
            matchesReviewedCondition(
                { ...question, inputKind: 'NUMBER', constraints: { min: 19 } },
                updated,
            ),
        ).toBe(true);
        expect(
            matchesReviewedCondition(
                { ...question, options: [{ value: 'YES', label: '예' }] },
                updated,
            ),
        ).toBe(false);
    });

    it('rejects a changed selection kind or option meaning even when the wording and source match', () => {
        const options = [
            { value: 'YES', label: '제출 대상이에요' },
            { value: 'NO', label: '제출 대상이 아니에요' },
        ];
        const current: Question = { ...question, inputKind: 'SINGLE', options };
        const reviewed: ReviewedConditionPresentation = {
            ...entry,
            answerContract: { inputKind: 'SINGLE', options },
        };
        expect(matchesReviewedCondition(current, reviewed)).toBe(true);
        expect(
            matchesReviewedCondition(
                { ...current, inputKind: 'MULTIPLE' },
                reviewed,
            ),
        ).toBe(false);
        for (const change of [
            { label: '준비에 확인이 필요해요' },
            { requiresFollowUp: true },
            { value: 'NOT_APPLICABLE' },
        ]) {
            expect(
                matchesReviewedCondition(
                    {
                        ...current,
                        options: [options[0], { ...options[1], ...change }],
                    },
                    reviewed,
                ),
            ).toBe(false);
        }
    });

    it('normalizes optional defaults and ignores object property order', () => {
        const reviewed: ReviewedConditionPresentation = {
            ...entry,
            answerContract: {
                inputKind: null,
                options: [
                    { value: 'YES', label: '예', requiresFollowUp: false },
                ],
                constraints: { max: 39, min: 19, unit: null },
                answerDependencies: null,
            },
        };
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    options: [{ label: '예', value: 'YES' }],
                    constraints: { min: 19, max: 39 },
                    answerDependencies: [],
                },
                reviewed,
            ),
        ).toBe(true);
        expect(
            matchesReviewedCondition(
                {
                    ...question,
                    ...reviewed.answerContract,
                    constraints: { min: 19, max: 49 },
                },
                reviewed,
            ),
        ).toBe(false);
    });

    it('requires the exact reviewed applicability even when title, source and choices are unchanged', () => {
        const applicability = {
            questionId: 'application-type',
            answerValues: ['NEWLYWED'],
        };
        const scoped = { ...question, applicability };
        expect(matchesReviewedCondition(scoped, entry)).toBe(false);
        const reviewed = { ...entry, answerContract: { applicability } };
        expect(matchesReviewedCondition(scoped, reviewed)).toBe(true);
        expect(
            matchesReviewedCondition(
                {
                    ...scoped,
                    applicability: {
                        ...applicability,
                        answerValues: ['PREMARITAL'],
                    },
                },
                reviewed,
            ),
        ).toBe(false);
        expect(matchesReviewedCondition(question, reviewed)).toBe(false);
    });

    it('rejects an older dependency contract without changing the saved question', () => {
        const dependency = {
            questionId: 'category',
            answerValues: ['MARRIED'],
            conflictingValues: ['NONE'],
            requiredDocumentIds: ['marriage-proof'],
            message: '신청 유형과 가족관계를 다시 확인해요.',
        };
        const reviewed = {
            ...entry,
            answerContract: { answerDependencies: [dependency] },
        };
        const saved = structuredClone(question);
        expect(matchesReviewedCondition(saved, reviewed)).toBe(false);
        expect(saved).toEqual(question);
        expect(
            matchesReviewedCondition(
                { ...saved, answerDependencies: [dependency] },
                reviewed,
            ),
        ).toBe(true);
        expect(
            matchesReviewedCondition(
                {
                    ...saved,
                    answerDependencies: [
                        { ...dependency, conflictingValues: ['NO'] },
                    ],
                },
                reviewed,
            ),
        ).toBe(false);
    });

    it.each([
        ['광주두암4', duamCatalog, 'applies-eligibility-proof'],
        ['담양삼만', damyangCatalog, 'applies-work'],
    ])(
        'preserves the old YES/NO meaning for %s history',
        (_, catalog, questionId) => {
            const reviewed = catalog.entries.find(
                (item) => item.questionId === questionId,
            ) as ReviewedConditionPresentation;
            const old: Question = {
                id: questionId,
                title: reviewed.sourceTitle,
                reason: reviewed.sourceReason,
                evidence: {
                    label: '공식 근거',
                    url: reviewed.sourceEvidence.url,
                    checkedAt: '2026-11-21',
                },
                inputKind: 'SINGLE',
                options: [
                    {
                        value: 'YES',
                        label: '제출 대상이에요',
                        requiresFollowUp: false,
                    },
                    {
                        value: 'NO',
                        label: '제출 대상이 아니에요',
                        requiresFollowUp: false,
                    },
                    {
                        value: 'UNKNOWN',
                        label: '잘 모르겠어요',
                        requiresFollowUp: true,
                    },
                ],
            };
            expect(reviewed.answerContract).toBeDefined();
            expect(matchesReviewedCondition(old, reviewed)).toBe(false);
            expect(
                matchesReviewedCondition(
                    {
                        ...old,
                        ...reviewed.answerContract,
                        title:
                            reviewed.acceptedTitles?.at(-1) ??
                            reviewed.sourceTitle,
                        reason:
                            reviewed.acceptedReasons?.at(-1) ??
                            reviewed.sourceReason,
                    },
                    reviewed,
                ),
            ).toBe(true);
        },
    );
});

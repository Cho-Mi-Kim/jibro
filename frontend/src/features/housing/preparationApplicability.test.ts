import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import type { Notice, Preparation, Question } from './types';
import {
    applicantInputQuestions,
    visiblePreparationQuestions,
    visiblePreparationDocuments,
    reviewedPreparationScope,
    preparationScheduleSelection,
} from './preparationApplicability';
import { assessDemoPreparation } from './demoPreparationRules';

const cases = JSON.parse(
    readFileSync('../data/contracts/preparation-applicability.json', 'utf8'),
) as {
    name: string;
    preparation: Preparation;
    expectedQuestionIds: string[];
    expectedScheduleIds: string[];
    expectedDocumentIds?: string[];
    expectedInputQuestionIds?: string[];
}[];

describe('실제 서버와 시연의 답변별 표시 범위', () => {
    it.each(cases)(
        '$name',
        ({
            preparation,
            expectedQuestionIds,
            expectedScheduleIds,
            expectedDocumentIds,
            expectedInputQuestionIds,
        }) => {
            const before = structuredClone(preparation);
            expect(
                visiblePreparationQuestions(preparation).map((item) => item.id),
            ).toEqual(expectedQuestionIds);
            expect(
                preparationScheduleSelection(
                    preparation,
                    preparation.schedules,
                ).visible.map((item) => item.id),
            ).toEqual(expectedScheduleIds);
            if (expectedDocumentIds)
                expect(
                    visiblePreparationDocuments(preparation).map((d) => d.id),
                ).toEqual(expectedDocumentIds);
            if (expectedInputQuestionIds)
                expect(
                    applicantInputQuestions(preparation).map((q) => q.id),
                ).toEqual(expectedInputQuestionIds);
            expect(preparation).toEqual(before);
        },
    );

    it('미해당 회차는 시연 진행률에서 제외하고 답변을 바꾸면 다시 준비할 일정이 된다', () => {
        const p = structuredClone(
            cases.find(
                (entry) => entry.preparation.answers.method === 'ONLINE',
            )!.preparation,
        );
        p.id = 'p';
        p.noticeId = 'n';
        p.documents = [];
        p.schedules = p.schedules.map((schedule) => ({
            ...schedule,
            recorded: !schedule.title.includes('현장 접수'),
        }));
        const selected = assessDemoPreparation(p);
        const all = assessDemoPreparation({
            ...p,
            answers: { ...p.answers, method: 'UNKNOWN' },
        });
        expect(selected.progress).toBeGreaterThan(all.progress);
        expect(p.schedules.some((schedule) => !schedule.recorded)).toBe(true);
    });

    it('서류는 공통·해당·미확인을 유지하고 안내 상태와 관계없이 확실한 미해당만 제외한다', () => {
        const p = structuredClone(cases[0].preparation);
        const document = p.documents[0];
        p.documents = [
            { ...document, id: 'common', necessity: 'REQUIRED' },
            { ...document, id: 'selected', necessity: 'CONDITIONAL' },
            { ...document, id: 'unknown', necessity: 'UNKNOWN' },
            { ...document, id: 'other', necessity: 'NOT_APPLICABLE' },
        ];
        p.documentConditions = {};
        const before = structuredClone(p);
        expect(visiblePreparationDocuments(p).map((d) => d.id)).toEqual([
            'common',
            'selected',
            'unknown',
        ]);
        expect(
            visiblePreparationDocuments({
                ...p,
                guideState: 'CHANGE_REVIEW',
            }).map((d) => d.id),
        ).toEqual(['common', 'selected', 'unknown']);
        expect(p).toEqual(before);
    });

    it('공식 확인 중인 서류와 연결 질문은 같은 제외 기준으로 보호한다', () => {
        const p = structuredClone(cases[0].preparation);
        p.questions = [
            {
                id: 'extra',
                title: '추가 서류는 누가 내나요?',
                reason: '해당자 서류 안내',
                evidence: p.questions[0].evidence,
                options: [
                    { value: 'YES', label: '해당해요' },
                    { value: 'NO', label: '해당하지 않아요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ];
        p.answers = { extra: 'NO' };
        p.documents = [
            { ...p.documents[0], id: 'extra-doc', title: '추가 서류' },
        ];
        p.documentConditions = { 'extra-doc': 'extra' };
        expect(visiblePreparationDocuments(p)).toEqual([]);
        expect(visiblePreparationQuestions(p)).toEqual([]);

        for (const protection of [
            { documents: [{ ...p.documents[0], reviewRequired: true }] },
            { questions: [{ ...p.questions[0], reviewRequired: true }] },
            {
                reviewIssues: [
                    {
                        id: 'review',
                        area: 'DOCUMENT' as const,
                        title: '공식 확인',
                        detail: '기준 확인',
                        questionIds: [],
                        documentIds: ['extra-doc'],
                        evidence: [],
                    },
                ],
            },
            {
                reviewIssues: [
                    {
                        id: 'review',
                        area: 'CONDITION' as const,
                        title: '공식 확인',
                        detail: '기준 확인',
                        questionIds: ['extra'],
                        documentIds: [],
                        evidence: [],
                    },
                ],
            },
            {
                reviewIssues: [
                    {
                        id: 'review',
                        area: 'DOCUMENT' as const,
                        title: '공식 확인',
                        detail: '기준 확인',
                        questionIds: [],
                        documentIds: [],
                        evidence: [],
                    },
                ],
            },
        ]) {
            const reviewed = { ...p, ...protection };
            expect(
                visiblePreparationDocuments(reviewed).map((d) => d.id),
            ).toEqual(['extra-doc']);
            expect(
                visiblePreparationQuestions(reviewed).map((q) => q.id),
            ).toEqual(['extra']);
        }
    });

    it('공개 검수와 서류 연결을 합쳐도 저장된 검수를 해제하거나 원본을 바꾸지 않는다', () => {
        const p = structuredClone(cases[0].preparation);
        p.questions[0].reviewRequired = true;
        const document = p.documents[0];
        const currentQuestion = {
            ...p.questions[0],
            id: 'current-question',
            reviewRequired: true,
        };
        const notice = {
            questions: [
                { ...p.questions[0], reviewRequired: false },
                currentQuestion,
            ],
            documentTemplates: [
                { ...document, conditionQuestionId: 'current-question' },
            ],
            reviewIssues: [],
        };
        const before = structuredClone({ p, notice });
        const scope = reviewedPreparationScope(p, notice);
        expect(scope.questions[0].reviewRequired).toBe(true);
        expect(scope.documentConditions?.[document.id]).toBe(
            'current-question',
        );
        expect(visiblePreparationDocuments(scope).map((d) => d.id)).toEqual([
            'finance',
            'marriage',
            'premarital',
        ]);
        expect(scope.id).toBe(p.id);
        expect({ p, notice }).toEqual(before);

        const oldReviewOnly = reviewedPreparationScope(p, {
            ...notice,
            questions: [{ ...currentQuestion, reviewRequired: false }],
        });
        expect(
            visiblePreparationDocuments(oldReviewOnly).map((d) => d.id),
        ).toContain('finance');
    });
});

describe('신청 유형에 맞춘 입력 질문', () => {
    const questions = JSON.parse(
        readFileSync(
            '../data/notices/pohang-bluevalley-answer-consistency-20261121.json',
            'utf8',
        ),
    ).content.questions as Question[];
    const scope = { questions, answers: { category: 'YOUTH' }, documents: [] };

    it('다른 유형의 보관 답변 때문에 시연 진행률과 다음 행동이 미완료로 남지 않는다', () => {
        const p = structuredClone(cases[0].preparation);
        p.id = 'current';
        p.questions = questions.filter((q) =>
            ['category', 'work'].includes(q.id),
        );
        p.documents = [];
        p.schedules = [];
        p.documentConditions = {};
        for (const work of ['UNKNOWN', 'DOES_NOT_MEET']) {
            p.answers = { category: 'YOUTH', work };
            const result = assessDemoPreparation(p);
            expect(result.progress).toBe(30);
            expect(result.warnings).toEqual([]);
            expect(result.nextAction.path).toBe(
                '/preparations/current/schedule',
            );
            const restored = assessDemoPreparation({
                ...p,
                answers: { category: 'INDUSTRIAL', work },
            });
            expect(restored.progress).toBe(15);
            expect(restored.nextAction.path).toBe(
                '/preparations/current/conditions/edit',
            );
            expect(p.answers).toEqual({ category: 'YOUTH', work });
        }
    });

    it('청년은 산단 전용 질문을 건너뛰고 유형을 바꾸면 다시 나타나며 원본을 보존한다', () => {
        const before = structuredClone(scope);
        expect(applicantInputQuestions(scope).map((q) => q.id)).toEqual([
            'category',
            'household',
            'income-assets',
            'selection',
            'renewal',
            'applies-finance',
            'applies-assets',
            'applies-family',
            'applies-pregnancy',
            'applies-youth',
            'applies-subscription',
            'applies-proxy',
        ]);
        const industrial = { ...scope, answers: { category: 'INDUSTRIAL' } };
        expect(applicantInputQuestions(industrial).map((q) => q.id)).toEqual([
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
            'applies-work',
            'applies-subscription',
            'applies-proxy',
        ]);
        expect(
            visiblePreparationQuestions(scope).map((q) => q.id),
        ).not.toContain('work');
        expect(scope).toEqual(before);
    });

    it('입력 중 자기 답변이나 서류의 비해당 상태로 현재 질문을 없애지 않는다', () => {
        for (const work of ['NOT_MY_TYPE', 'UNKNOWN', 'DOES_NOT_MEET']) {
            expect(
                applicantInputQuestions({
                    ...scope,
                    answers: { category: 'INDUSTRIAL', work },
                }).map((q) => q.id),
            ).toContain('work');
        }
        const p = {
            ...scope,
            answers: { category: 'YOUTH', 'applies-family': 'NONE' },
            documents: structuredClone(cases[0].preparation.documents),
        };
        expect(applicantInputQuestions(p).map((q) => q.id)).toContain(
            'applies-family',
        );
        expect(applicantInputQuestions(p).map((q) => q.id)).toContain(
            'applies-finance',
        );
    });

    it('유형을 확정하지 못했거나 검수·모순이면 관련 질문을 유지한다', () => {
        for (const category of ['UNKNOWN', 'NONE', 'MISSING'])
            expect(
                applicantInputQuestions({ ...scope, answers: { category } }),
            ).toEqual(questions);
        expect(applicantInputQuestions({ ...scope, answers: {} })).toEqual(
            questions,
        );
        expect(
            applicantInputQuestions({ ...scope, guideState: 'CHANGE_REVIEW' }),
        ).toEqual(questions);
        expect(
            applicantInputQuestions({
                ...scope,
                questions: questions.map((q) =>
                    q.id === 'category' ? { ...q, reviewRequired: true } : q,
                ),
            }),
        ).toHaveLength(questions.length);
        const reviewed = {
            ...scope,
            questions: questions.map((q) =>
                q.id === 'work' ? { ...q, reviewRequired: true } : q,
            ),
        };
        expect(applicantInputQuestions(reviewed).map((q) => q.id)).toContain(
            'work',
        );
        expect(
            applicantInputQuestions({
                ...scope,
                answers: { category: 'YOUTH', 'applies-family': 'NONE' },
            }),
        ).toEqual(questions);
        expect(
            applicantInputQuestions({
                ...scope,
                reviewIssues: [
                    {
                        id: 'broad',
                        area: 'CONDITION',
                        title: '확인 필요',
                        detail: '기준 대조 중',
                        questionIds: [],
                        documentIds: [],
                        evidence: [],
                    },
                ],
            }),
        ).toEqual(questions);
    });

    it('공통 비교·부정 문구와 다른 분류 축을 특정 유형 전용 질문으로 해석하지 않는다', () => {
        const titles = [
            '청년이 아니어도 신청하나요?',
            '청년과 신혼부부 기준 차이는 무엇인가요?',
            '일반공급 순위와 우선공급 자격을 구분했나요?',
            '임신·입양은 어떤 서류로 확인하나요?',
        ];
        const extra = titles.map((title, index) => ({
            ...questions[1],
            id: `common-${index}`,
            title,
        }));
        expect(
            applicantInputQuestions({
                ...scope,
                questions: [...questions, ...extra],
            })
                .filter((q) => q.id.startsWith('common-'))
                .map((q) => q.title),
        ).toEqual(titles);
    });
});

describe('공개 원문 질문과 선택지로 분기하는 다른 공고', () => {
    const notices = JSON.parse(
        readFileSync('src/app/fixtures/applicant-branch-notices.json', 'utf8'),
    ) as Notice[];
    it('울산의 예비신혼과 대학생·취업준비생은 생략된 하위 계층도 보존한다', () => {
        const notice = notices.find((n) => n.title.includes('울산다운2'))!;
        const before = structuredClone(notice);
        const ids = (value: string) =>
            applicantInputQuestions({
                ...notice,
                answers: { 'review-category': value },
            }).map((q) => q.id);
        expect(ids('PREMARITAL')).toContain('family');
        expect(ids('PREMARITAL')).not.toContain('student');
        expect(ids('PREMARITAL')).not.toContain('youth');
        expect(ids('STUDENT')).toContain('student');
        expect(ids('JOBSEEKER')).toContain('student');
        expect(ids('STUDENT')).not.toContain('family');
        expect(applicantInputQuestions({ ...notice, answers: {} })[0].id).toBe(
            'review-category',
        );
        expect(notice).toEqual(before);
    });

    it('직접 연결된 전용 서류의 미입력은 진행률·다음 행동을 막지 않으며 유형 변경으로 복원된다', () => {
        const p = structuredClone(
            cases.find((c) => c.name.includes('직접 연결된 미입력 서류'))!
                .preparation,
        );
        p.id = 'current';
        const before = structuredClone(p);
        const selected = assessDemoPreparation(p);
        expect(selected.progress).toBe(80);
        expect(selected.nextAction.path).toBe('/preparations/current/schedule');
        const restored = assessDemoPreparation({
            ...p,
            answers: { 'family-new2-category': 'SINGLE_PARENT' },
        });
        expect(restored.progress).toBe(40);
        expect(restored.nextAction.path).toBe(
            '/preparations/current/conditions/edit',
        );
        expect(p).toEqual(before);
    });
});

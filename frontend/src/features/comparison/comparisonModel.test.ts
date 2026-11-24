import { describe, expect, it } from 'vitest';
import {
    comparisonSchedules,
    personalizeComparison,
    selectedNotices,
    selectComparisonNotice,
} from './comparisonModel';
import { comparisonFixture } from './comparisonFixtures';
import type { NoticeComparison } from './comparisonApi';
import type { Preparation } from '../housing/types';

function savedPreparation(data: NoticeComparison): Preparation {
    return {
        id: 'preparation-A',
        noticeId: data.notice.id,
        title: data.notice.title,
        version: data.notice.version,
        revision: 1,
        guideState: 'AVAILABLE',
        questions: data.notice.questions,
        answers: { residence: 'OTHER' },
        documents: (data.documentRequirements ?? []).map((doc) => ({
            ...doc,
            necessity: doc.conditionQuestionId ? 'NOT_APPLICABLE' : 'REQUIRED',
            state: 'UNCHECKED',
            note: '보존할 메모',
        })),
        documentConditions: Object.fromEntries(
            (data.documentRequirements ?? []).flatMap((doc) =>
                doc.conditionQuestionId
                    ? [[doc.id, doc.conditionQuestionId]]
                    : [],
            ),
        ),
        schedules: (data.notice.schedules ?? []).map((event) => ({
            ...event,
            recorded: true,
        })),
        progress: 30,
        houseStage: 2,
        warnings: [],
        nextAction: {
            label: '다음 준비',
            reason: '공식 안내',
            path: '/preparations',
        },
    };
}

describe('공개 공고 비교 모델', () => {
    it('중복 URL 선택은 한 공고로 보고 재선택·해제 시 다른 검색값을 보존한다', () => {
        const params = new URLSearchParams('a=A&b=A&from=notice');
        expect(selectedNotices(params)).toEqual({ a: 'A', b: undefined });
        expect(selectComparisonNotice(params, 'b', 'B').toString()).toBe(
            'a=A&b=B&from=notice',
        );
        expect(selectComparisonNotice(params, 'a').toString()).toBe(
            'b=A&from=notice',
        );
        expect(params.toString()).toBe('a=A&b=A&from=notice');
    });

    it('공개 일정의 시각과 추가 회차를 보존하고 같은 기본 기한은 중복시키지 않는다', () => {
        const { notice } = comparisonFixture('A');
        notice.schedules!.push(
            {
                id: 'plain',
                title: '접수 마감',
                date: '2026-12-31',
                evidence: notice.evidence,
            },
            {
                id: 'other',
                title: '2차 결과 발표',
                date: '2027-02-02',
                evidence: notice.evidence,
            },
        );
        const rows = comparisonSchedules(notice);
        expect(rows.find((row) => row.id === 'end')).toMatchObject({
            date: '2026-12-31T18:00:00',
        });
        expect(rows.some((row) => row.id === 'plain')).toBe(false);
        expect(rows.find((row) => row.id === 'detail:other')?.date).toBe(
            '2027-02-02',
        );
        expect(rows.find((row) => row.id === 'documents')?.date).toBeNull();
    });

    it('확인되지 않은 날짜를 만들어내지 않고 시차 있는 일정은 한국 날짜·시각을 유지한다', () => {
        const { notice } = comparisonFixture('A');
        notice.startDate = null;
        notice.endDate = '2026-12-31';
        notice.schedules = [
            {
                id: 'closing',
                title: '접수 마감',
                date: '2026-12-30T15:00:00Z',
                evidence: notice.evidence,
            },
        ];
        const rows = comparisonSchedules(notice);
        expect(rows.find((row) => row.id === 'start')?.date).toBeNull();
        expect(rows.find((row) => row.id === 'end')?.date).toBe(
            '2026-12-30T15:00:00Z',
        );
    });
});

describe('저장 답변으로 개인화한 비교 자료', () => {
    it('확실한 비해당 조건만 제외하고 공개 질문의 본문과 새 항목을 유지한다', () => {
        const data = comparisonFixture('A');
        const evidence = data.notice.evidence;
        data.notice.questions = [
            data.notice.questions[0],
            {
                id: 'work',
                title: '산업단지 근로자 기준',
                reason: '공개 원문 설명',
                evidence,
                options: [
                    { value: 'MEETS', label: '해당해요' },
                    { value: 'NOT_MY_TYPE', label: '다른 유형으로 신청해요' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ];
        const preparation = savedPreparation(data);
        preparation.answers.work = 'NOT_MY_TYPE';
        data.notice.questions = [
            { ...data.notice.questions[0], reason: '공개본의 최신 표시 설명' },
            data.notice.questions[1],
            {
                id: 'new-public',
                title: '추가 공개 조건',
                reason: '추가 설명',
                evidence,
            },
        ];
        const original = JSON.stringify({ data, preparation });
        const result = personalizeComparison(data, preparation);

        expect(result.notice.questions.map((q) => q.id)).toEqual([
            'residence',
            'new-public',
        ]);
        expect(result.notice.questions[0]).toBe(data.notice.questions[0]);
        expect(result.documentRequirements?.map((d) => d.id)).toEqual([
            'common',
        ]);
        expect(result.notice.documents).toEqual(['A 주민등록등본']);
        expect(JSON.stringify({ data, preparation })).toBe(original);
    });

    it('공개·저장 검수와 질문 검수 표시는 비해당 문서와 조건보다 우선한다', () => {
        const data = comparisonFixture('A');
        const preparation = savedPreparation(data);
        data.notice.questions[0] = {
            ...data.notice.questions[0],
            reviewRequired: true,
        };
        data.notice.reviewIssues = [
            {
                id: 'public-question',
                area: 'CONDITION',
                title: '거주 기준 확인',
                detail: '공식 기준 확인',
                questionIds: ['residence'],
                documentIds: [],
                evidence: [data.notice.evidence],
            },
        ];
        preparation.reviewIssues = [
            {
                id: 'saved-document',
                area: 'DOCUMENT',
                title: '추가 서류 확인',
                detail: '공식 서류 확인',
                questionIds: [],
                documentIds: ['conditional'],
                evidence: [data.notice.evidence],
            },
        ];
        const result = personalizeComparison(data, preparation);
        expect(result.notice.questions.map((q) => q.id)).toEqual(['residence']);
        expect(result.documentRequirements?.map((d) => d.id)).toEqual([
            'common',
            'conditional',
        ]);
        expect(result.notice.documents).toEqual([
            'A 주민등록등본',
            'A 추가 서류',
        ]);

        data.notice.reviewIssues = [
            {
                ...data.notice.reviewIssues[0],
                area: 'DOCUMENT',
                questionIds: [],
            },
        ];
        data.notice.questions[0] = {
            ...data.notice.questions[0],
            reviewRequired: false,
        };
        preparation.reviewIssues = [];
        expect(
            personalizeComparison(data, preparation).documentRequirements,
        ).toHaveLength(2);
    });

    it('생략된 서류를 다시 채우고 공개 자료에 없는 이름이나 생략 개수를 추측하지 않는다', () => {
        const data = comparisonFixture('A');
        const template = data.documentRequirements![1];
        const titles = [
            '제외1',
            '제외2',
            '제외3',
            '생략제외',
            '유지',
            '새공개',
        ];
        data.documentRequirements = titles.map((title, i) => ({
            ...template,
            id: `d${i}`,
            title,
        }));
        data.notice.documentTemplates = data.documentRequirements;
        data.notice.documents = [...titles, '명칭만 공개된 서류'];
        data.notice.documentSummary = {
            common: { items: [], remainingCount: 0 },
            conditional: {
                items: data.documentRequirements
                    .slice(0, 3)
                    .map(({ id, title, evidence }) => ({
                        id,
                        title,
                        evidence,
                    })),
                remainingIds: ['d3', 'd4', 'd5', 'unresolved-id'],
                remainingCount: 6,
            },
            reviewRequiredCount: 0,
        };
        const preparation = savedPreparation(data);
        preparation.documents = preparation.documents
            .slice(0, 5)
            .map((doc) => ({
                ...doc,
                necessity: doc.id === 'd4' ? 'CONDITIONAL' : 'NOT_APPLICABLE',
            }));
        const original = JSON.stringify({ data, preparation });
        const result = personalizeComparison(data, preparation);

        expect(result.documentRequirements?.map((d) => d.id)).toEqual([
            'd4',
            'd5',
        ]);
        expect(result.notice.documentTemplates?.map((d) => d.id)).toEqual([
            'd4',
            'd5',
        ]);
        expect(result.notice.documents).toEqual([
            '유지',
            '새공개',
            '명칭만 공개된 서류',
        ]);
        expect(result.notice.documentSummary?.conditional).toEqual({
            items: [
                { id: 'd4', title: '유지', evidence: data.notice.evidence },
                { id: 'd5', title: '새공개', evidence: data.notice.evidence },
            ],
            remainingIds: ['unresolved-id'],
            remainingCount: 3,
        });
        expect(JSON.stringify({ data, preparation })).toBe(original);

        data.notice.documentSummary.conditional.remainingIds = undefined;
        expect(
            personalizeComparison(data, preparation).notice.documentSummary
                ?.conditional.remainingCount,
        ).toBe(6);
    });

    it('선택한 접수 방식의 회차와 대표 마감을 함께 표시하고 공통 일정은 남긴다', () => {
        const data = comparisonFixture('A');
        const evidence = data.notice.evidence;
        data.notice.questions = [
            {
                id: 'method',
                title: '어떤 방법으로 신청하나요?',
                reason: '공식 접수 방법',
                evidence,
                options: [
                    { value: 'ONLINE', label: '온라인 신청' },
                    { value: 'VISIT', label: '현장 신청' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ];
        data.notice.schedules = [
            {
                id: 'end',
                title: '신청 접수 마감',
                date: '2026-12-31',
                evidence,
            },
            {
                id: 'online',
                title: '접수 회차 · 온라인 접수 마감',
                date: '2026-12-20T16:00:00',
                evidence,
            },
            {
                id: 'visit',
                title: '접수 회차 · 현장 접수 마감',
                date: '2026-12-31T18:00:00',
                evidence,
            },
            {
                id: 'contract',
                title: '접수 회차 · 현장 동호지정 및 계약',
                date: '2027-01-05',
                evidence,
            },
        ];
        const preparation = savedPreparation(data);
        preparation.answers = { method: 'ONLINE' };
        const original = JSON.stringify({ data, preparation });
        const result = personalizeComparison(data, preparation);
        expect(result.notice.schedules?.map((event) => event.id)).toEqual([
            'end',
            'online',
            'contract',
        ]);
        expect(
            comparisonSchedules(result.notice).find(
                (event) => event.id === 'end',
            )?.date,
        ).toBe('2026-12-20T16:00:00');
        expect(JSON.stringify({ data, preparation })).toBe(original);

        preparation.answers.method = 'UNKNOWN';
        expect(
            personalizeComparison(data, preparation).notice.schedules?.map(
                (event) => event.id,
            ),
        ).toEqual(['end', 'online', 'visit', 'contract']);
    });
});

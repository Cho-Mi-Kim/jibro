import { describe, expect, it } from 'vitest';
import type { Preparation, Schedule } from './types';
import {
    applicationRecordSchedule,
    dateWithScheduleTime,
    scheduleDay,
    scheduleKind,
    scheduleMilestone,
    selectSchedule,
    snapshotPreparationScheduleSource,
} from './schedules';

describe('공식 일정의 의미', () => {
    it.each([
        ['당첨자 발표', 'result'],
        ['예비입주자 발표', 'result'],
        ['서류 제출 대상자 발표', 'result'],
        ['결과 발표', 'result'],
        ['접수 마감 (18:00)', 'end'],
        ['서류 제출 마감', 'documents'],
        ['신청 접수 개시', 'start'],
    ])('%s를 %s로 분류한다', (title, kind) => {
        expect(scheduleKind(title)).toBe(kind);
    });

    it('같은 날의 상세 시각을 선택하고 다른 날의 마감은 합치지 않는다', () => {
        const events = [
            { title: '신청 접수 마감', date: '2026-12-30' },
            { title: '신청 접수 마감', date: '2026-12-31' },
            { title: '접수 마감 (18:00)', date: '2026-12-31' },
        ];
        const closing = selectSchedule(events, 'end', '2026-12-31');
        expect(closing).toEqual(events[2]);
        expect(dateWithScheduleTime('2026-12-31', closing)).toBe(
            '2026-12-31T18:00:00',
        );
        expect(dateWithScheduleTime('2026-12-30', closing)).toBe('2026-12-30');
    });

    it('발표 칸은 시각이 있는 당첨자 발표보다 서류 제출 대상자 발표를 우선한다', () => {
        const events = [
            { title: '당첨자 발표 (18:00)', date: '2027-02-02' },
            { title: '서류 제출 대상자 발표', date: '2027-01-05' },
        ];
        expect(selectSchedule(events, 'result')).toBe(events[1]);
    });

    it('순위별 발표일을 공통 날짜로 단정하지 않고 같은 날의 공통 중복은 하나로 표시한다', () => {
        const evidence = {
            label: '공고문 6쪽',
            url: 'https://apply.lh.or.kr/',
            checkedAt: '2026-11-23',
        };
        const first = {
            id: 'priority',
            title: '1순위 우선 결과 발표 (17:00 이후)',
            date: '2026-12-22T17:00',
            evidence,
        };
        const second = {
            id: 'general',
            title: '1순위 일반·2순위 결과 발표 (17:00 이후)',
            date: '2027-01-27T17:00',
            evidence,
        };
        const source = { schedules: [first, second] };
        expect(scheduleMilestone(source, 'result')).toEqual({
            date: null,
            event: undefined,
            alternatives: [first, second],
        });
        expect(
            scheduleMilestone(
                { schedules: [first, { ...second, date: first.date }] },
                'result',
            ).alternatives,
        ).toHaveLength(2);
        const common = { ...first, title: '결과 발표' };
        const duplicate = {
            ...second,
            title: '입주자 선정결과 발표',
            date: first.date,
        };
        expect(
            scheduleMilestone({ schedules: [common, duplicate] }, 'result'),
        ).toEqual({ date: first.date, event: common, alternatives: [] });
        const selection = { ...first, title: '서류 제출 대상자 발표' };
        expect(
            scheduleMilestone({ schedules: [selection, second] }, 'result')
                .date,
        ).toBe(first.date);
        expect(source.schedules).toEqual([first, second]);
    });

    it('신청 완료 기록이 있으면 우선하고 서류 마감을 신청 기록으로 사용하지 않는다', () => {
        const closing = { title: '접수 마감', date: '2026-12-31' };
        const application = {
            title: '공식 신청 여부 확인',
            date: '2026-12-31',
        };
        const documents = { title: '서류 제출 마감', date: '2027-01-05' };
        expect(
            applicationRecordSchedule([documents, closing, application]),
        ).toBe(application);
        expect(applicationRecordSchedule([documents, closing])).toBe(closing);
        expect(applicationRecordSchedule([documents])).toBeUndefined();
    });

    it('시간대가 있는 일정은 서울 날짜로 대조하고 잘못된 날짜는 제외한다', () => {
        const closing = { title: '접수 마감', date: '2026-12-30T15:00:00Z' };
        expect(scheduleDay(closing.date)).toBe('2026-12-31');
        expect(selectSchedule([closing], 'end', '2026-12-31')).toBe(closing);
        expect(
            selectSchedule(
                [{ title: '결과 발표', date: '2026-02-30' }],
                'result',
            ),
        ).toBeUndefined();
    });
});

describe('보관 당시 답변으로 선택한 일정', () => {
    const evidence = {
        label: '보관 당시 공고문',
        url: 'https://apply.lh.or.kr/',
        checkedAt: '2026-11-01',
    };
    const saved: Preparation = {
        id: 'saved',
        noticeId: 'old-notice',
        title: '당시 공고',
        version: 2,
        revision: 3,
        guideState: 'AVAILABLE',
        questions: [
            {
                id: 'method',
                title: '어떤 방법으로 신청하나요?',
                reason: '접수 방법',
                evidence,
                options: [
                    { value: 'ONLINE', label: '온라인 신청' },
                    { value: 'VISIT', label: '현장 신청' },
                    { value: 'UNKNOWN', label: '잘 모르겠어요' },
                ],
            },
        ],
        answers: { method: 'ONLINE' },
        documents: [],
        schedules: [],
        progress: 73,
        houseStage: 3,
        warnings: [],
        nextAction: { label: '당시 할 일', reason: '', path: '' },
    };
    const event = (
        id: string,
        title: string,
        date: string,
        recorded = false,
    ): Schedule => ({
        id,
        title,
        date,
        recorded,
        evidence,
    });
    const schedules = [
        event('start', '신청 접수 시작 (09:00)', '2026-11-10T09:00:00', true),
        event('end', '신청 접수 마감 (18:00)', '2026-11-20T18:00:00', true),
        event(
            'online-start',
            '접수 회차 · 온라인 접수 시작',
            '2026-11-11T10:00:00',
        ),
        event(
            'online-end',
            '접수 회차 · 온라인 접수 마감',
            '2026-11-19T16:00:00',
        ),
        event(
            'visit-start',
            '접수 회차 · 현장 접수 시작',
            '2026-11-10T09:00:00',
        ),
        event('visit-end', '접수 회차 · 현장 접수 마감', '2026-11-20T18:00:00'),
        event('result', '결과 발표', '2026-11-25'),
        event('contract', '접수 회차 · 현장 계약', '2026-11-30'),
    ];

    it('최신 공고 없이 당시 답변으로 기본 날짜와 회차를 함께 선택하고 원래 완료·진행률을 보존한다', () => {
        const p = { ...saved, schedules };
        const original = structuredClone(p);
        const source = snapshotPreparationScheduleSource(p);
        expect(source.schedules.map(({ id }) => id)).toEqual([
            'start',
            'end',
            'online-start',
            'online-end',
            'result',
            'contract',
        ]);
        expect(scheduleMilestone(source, 'start').date).toBe(
            '2026-11-11T10:00:00',
        );
        expect(scheduleMilestone(source, 'end').date).toBe(
            '2026-11-19T16:00:00',
        );
        expect(source.schedules.slice(0, 2)).toMatchObject([
            {
                title: '신청 접수 시작',
                date: '2026-11-11T10:00:00',
                recorded: true,
                evidence,
            },
            {
                title: '신청 접수 마감',
                date: '2026-11-19T16:00:00',
                recorded: true,
                evidence,
            },
        ]);
        expect(p).toEqual(original);
    });

    it('개인화한 한쪽 날짜와 다른 쪽 공통 날짜를 함께 보존하고 다른 방식만 확인된 경계는 미확인으로 남긴다', () => {
        const commonEnd = schedules.filter(
            ({ id }) => id !== 'online-end' && id !== 'visit-end',
        );
        const source = snapshotPreparationScheduleSource({
            ...saved,
            schedules: commonEnd,
        });
        expect(scheduleMilestone(source, 'start').date).toBe(
            '2026-11-11T10:00:00',
        );
        expect(scheduleMilestone(source, 'end').date).toBe(
            '2026-11-20T18:00:00',
        );
        const unknownEnd = snapshotPreparationScheduleSource({
            ...saved,
            schedules: schedules.filter(({ id }) => id !== 'online-end'),
        });
        expect(scheduleMilestone(unknownEnd, 'end').date).toBeNull();
        expect(
            unknownEnd.schedules.find(({ id }) => id === 'end'),
        ).toMatchObject({
            date: '',
            recorded: true,
        });
    });

    it('모름·검수·질문이 없는 구형 기록은 당시 일정과 완료 상태를 그대로 보여준다', () => {
        const records: Preparation[] = [
            { ...saved, schedules, answers: { method: 'UNKNOWN' } },
            { ...saved, schedules, guideState: 'CHANGE_REVIEW' as const },
            { ...saved, schedules, questions: [], answers: {} },
            {
                ...saved,
                schedules,
                reviewIssues: [
                    {
                        id: 'time',
                        area: 'SCHEDULE' as const,
                        title: '시각 확인 필요',
                        detail: '공식 일정의 시각을 확인해야 해요.',
                        questionIds: [],
                        documentIds: [],
                        evidence: [evidence],
                    },
                ],
            },
        ];
        for (const p of records) {
            const source = snapshotPreparationScheduleSource(p);
            expect(source.schedules).toEqual(schedules);
            expect(scheduleMilestone(source, 'end').date).toBe(
                '2026-11-20T18:00:00',
            );
        }
        expect(
            snapshotPreparationScheduleSource({ ...saved, schedules: [] })
                .schedules,
        ).toEqual([]);
    });
});

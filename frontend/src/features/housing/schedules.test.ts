import { describe, expect, it } from 'vitest';
import {
    applicationRecordSchedule,
    dateWithScheduleTime,
    scheduleDay,
    scheduleKind,
    selectSchedule,
} from './schedules';

describe('공식 일정의 의미', () => {
    it.each([
        ['당첨자 발표', 'result'],
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

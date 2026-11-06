import { describe, expect, it } from 'vitest';
import { daysLeft, displayDate, nextSchedule } from './housingHelpers';
describe('공고 일정 표시', () => {
    it('한국 날짜 자정 경계에서 D-Day를 계산한다', () => {
        expect(daysLeft('2026-11-02', new Date('2026-11-01T14:59:59Z'))).toBe(
            'D-1',
        );
        expect(daysLeft('2026-11-02', new Date('2026-11-01T15:00:00Z'))).toBe(
            'D-Day',
        );
        expect(daysLeft('2026-10-31', new Date('2026-11-01T00:00:00Z'))).toBe(
            '일정 경과',
        );
    });
    it('미제공 날짜를 만들어 내지 않고 시각의 분을 보존한다', () => {
        expect(daysLeft(null)).toBe('일정 확인');
        expect(daysLeft('invalid')).toBe('일정 확인');
        expect(displayDate(null)).toBe('확인 필요');
        expect(displayDate('2026-11-03T18:00:00')).toBe('2026.11.03 18:00');
        expect(displayDate('2026-11-03')).toBe('2026.11.03');
    });
});

it('접수 마감이 지나면 다음 공식 절차를 표시하고 경과한 날짜나 없는 시간을 만들지 않는다', () => {
    const today = new Date('2026-11-03T00:00:00+09:00');
    const steps = [
        { title: '후속 발표', date: '2026-11-07' },
        { title: '서류 마감', date: '2026-11-03' },
    ];
    expect(nextSchedule(steps, '2026-11-01', today)).toEqual(steps[1]);
    expect(nextSchedule([], '2026-11-01', today)).toBeUndefined();
});

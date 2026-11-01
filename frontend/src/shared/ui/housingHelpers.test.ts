import { describe, expect, it } from 'vitest';
import { daysLeft, displayDate } from './housingHelpers';
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

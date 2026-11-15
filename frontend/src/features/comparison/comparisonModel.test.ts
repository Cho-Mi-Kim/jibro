import { describe, expect, it } from 'vitest';
import {
    comparisonSchedules,
    selectedNotices,
    selectComparisonNotice,
} from './comparisonModel';
import { comparisonFixture } from './comparisonFixtures';

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

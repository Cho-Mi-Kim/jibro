import { describe, it, expect } from 'vitest';
import { calendarFile, calendarItems } from './calendar';
import type { Notice } from '../housing/types';
const evidence = {
    url: 'https://apply.lh.or.kr/',
    label: '공식 공고',
    checkedAt: '2026-11-03',
};
const notice = {
    id: 'one',
    title: '긴 한글 제목, '.repeat(20),
    startDate: '2026-11-03',
    endDate: null,
    version: 3,
    guideState: 'AVAILABLE',
    evidence,
    schedules: [
        {
            id: 'announce',
            title: '대상자; 발표',
            date: '2026-11-04T10:00:00',
            evidence,
        },
    ],
} as Notice;
describe('공식 일정 내보내기', () => {
    it('날짜만 있으면 종일, 서울 시각은 UTC로 저장하고 한글 줄 길이를 제한한다', () => {
        const items = calendarItems(notice);
        const file = calendarFile(
            notice,
            items,
            new Date('2026-11-03T00:00:00Z'),
        );
        expect(file).toContain('DTSTART;VALUE=DATE:20261103');
        expect(file).toContain('DTSTART:20261104T010000Z');
        for (const line of file.split('\r\n'))
            expect(new TextEncoder().encode(line).length).toBeLessThanOrEqual(
                75,
            );
        expect(file).toContain('UID:one-schedule-announce@jibro.local');
        expect(file).toContain('SEQUENCE:3');
    });
    it('준비 안내가 검토 중이어도 공개 일정을 내보내고 출처 없는 일정은 제외한다', () => {
        expect(
            calendarItems({ ...notice, guideState: 'REVIEWING' }).map(
                (x) => x.id,
            ),
        ).toEqual(['start', 'schedule-announce']);
        expect(
            calendarItems({
                ...notice,
                evidence: { ...evidence, url: null },
                schedules: [],
            }),
        ).toEqual([]);
        const revision = { ...notice, version: 4 };
        expect(calendarFile(revision, calendarItems(revision))).toContain(
            'UID:one-schedule-announce@jibro.local',
        );
    });
    it('기본 마감과 같은 날의 상세 마감은 한 번 내보내고 다른 종류의 일정은 남긴다', () => {
        const n = {
            ...notice,
            endDate: '2026-12-31',
            schedules: [
                {
                    id: 'plain',
                    title: '신청 접수 마감',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'closing',
                    title: '신청 접수 마감 (18:00, 조기 중단 가능)',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'docs',
                    title: '서류 제출 마감',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'result',
                    title: '당첨자 발표',
                    date: '2026-12-31',
                    evidence,
                },
                {
                    id: 'regional',
                    title: '서울 지역 접수 마감',
                    date: '2026-12-31T17:00:00',
                    evidence,
                },
            ],
        };
        const items = calendarItems(n);
        expect(items.map((item) => item.id)).toEqual([
            'start',
            'end',
            'schedule-docs',
            'schedule-result',
            'schedule-regional',
        ]);
        expect(items[1]).toMatchObject({
            title: '신청 접수 마감 (18:00, 조기 중단 가능)',
            date: '2026-12-31T18:00:00',
        });
        const file = calendarFile(n, items);
        expect(file).toContain('UID:one-end@jibro.local');
        expect(file).toContain('DTSTART:20261231T090000Z');
        expect(file).not.toContain('schedule-closing');
    });
});

import { describe, expect, it } from 'vitest';
import type { Notice } from './types';
import { applicationDate } from './schedules';
import { daysLeft, nextNoticeSchedule, noticeDaysLeft } from './noticeDisplay';
import { noticeRefreshInterval } from './noticeRefresh';
import { calendarFile, calendarItems } from '../preparation/calendar';

const evidence = {
    label: '공식 공고',
    url: 'https://apply.lh.or.kr/',
    checkedAt: '2026-11-01',
};
const notice: Notice = {
    id: 'timed',
    title: '확인된 마감',
    agency: 'LH',
    region: '전국',
    type: '전세임대',
    status: 'OPEN',
    startDate: '2026-02-24',
    endDate: '2026-12-31',
    publishedDate: '2026-02-24',
    attachments: [],
    summary: '',
    version: 1,
    guideVersion: 1,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [],
    documents: [],
    schedules: [],
    applicationTiming: {
        startsAt: null,
        endsAt: '2026-12-31T09:00:00Z',
        nextChangeAt: '2026-12-31T09:00:00Z',
    },
};

describe('서버가 확인한 접수 시각', () => {
    it('18시 직전과 정각의 마감 표시를 구분하고 날짜만 있으면 시각을 만들지 않는다', () => {
        expect(noticeDaysLeft(notice, new Date('2026-12-31T08:59:59Z'))).toBe(
            'D-Day',
        );
        expect(noticeDaysLeft(notice, new Date('2026-12-31T09:00:00Z'))).toBe(
            '일정 경과',
        );
        expect(daysLeft('2026-12-31', new Date('2026-12-31T14:59:59Z'))).toBe(
            'D-Day',
        );
        expect(
            noticeDaysLeft(
                { ...notice, status: 'CLOSED' },
                new Date('2026-12-31T08:00:00Z'),
            ),
        ).toBe('일정 경과');
    });

    it('마감 뒤에는 신청 절차를 다음 일정으로 되살리지 않고 서류 마감을 남긴다', () => {
        const events = [
            { title: '신청 접수 마감 (18:00)', date: '2026-12-31' },
            { title: '서류 제출 마감', date: '2027-01-03' },
        ];
        expect(
            nextNoticeSchedule(
                { ...notice, status: 'CLOSED' },
                events,
                new Date('2026-12-31T09:00:00Z'),
            ),
        ).toEqual(events[1]);
    });

    it('화면과 캘린더는 서버의 같은 시각을 쓰고 날짜 전용 일정은 종일로 내보낸다', () => {
        expect(applicationDate(notice, 'end')).toBe('2026-12-31T09:00:00Z');
        const file = calendarFile(notice, calendarItems(notice));
        expect(file).toContain('DTSTART:20261231T090000Z');
        const noTime = {
            ...notice,
            applicationTiming: {
                startsAt: null,
                endsAt: null,
                nextChangeAt: null,
            },
        };
        expect(calendarFile(noTime, calendarItems(noTime))).toContain(
            'DTSTART;VALUE=DATE:20261231',
        );
    });

    it('가장 이른 상태 변경 시 재조회하며 지나간 응답으로 무한 즉시 요청하지 않는다', () => {
        const now = Date.parse('2026-12-31T08:59:00Z');
        expect(noticeRefreshInterval([notice], now)).toBe(60100);
        expect(noticeRefreshInterval([notice], now + 61000)).toBe(1000);
        expect(
            noticeRefreshInterval(
                [{ ...notice, applicationTiming: null }],
                now,
            ),
        ).toBe(false);
    });
});

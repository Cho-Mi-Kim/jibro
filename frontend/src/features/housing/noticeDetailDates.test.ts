import { describe, expect, it } from 'vitest';
import { noticeDetailDates } from './noticeDetailDates';
import type { Notice } from './types';

const evidence = {
    label: '공식 일정',
    url: 'https://apply.lh.or.kr/',
    checkedAt: '2026-11-04',
};
function notice(overrides: Partial<Notice> = {}): Notice {
    return {
        id: 'notice',
        title: '청년 전세임대 모집',
        agency: 'LH',
        region: '전국',
        type: '청년 전세임대',
        status: 'OPEN',
        startDate: '2026-01-02',
        endDate: '2026-12-31',
        publishedDate: '2025-12-30',
        attachments: [],
        summary: '공식 공고 안내입니다.',
        version: 3,
        guideVersion: 3,
        guideState: 'AVAILABLE',
        canStart: true,
        evidence,
        questions: [],
        documents: [],
        ...overrides,
    };
}

describe('공고 상세 일정 표시', () => {
    it('같은 날의 신청 마감 일정에서 시각을 읽고 다른 날짜의 시각은 사용하지 않는다', () => {
        const schedules = [
            {
                id: 'other',
                title: '신청 접수 마감 (17:00)',
                date: '2026-12-30',
                evidence,
            },
            {
                id: 'closing',
                title: '신청 접수 마감 (18:00, 조기 중단 가능)',
                date: '2026-12-31',
                evidence,
            },
        ];
        expect(noticeDetailDates(notice({ schedules })).end).toEqual({
            date: '2026.12.31',
            time: '18:00',
        });
        expect(
            noticeDetailDates(notice({ schedules: schedules.slice(0, 1) })).end
                .time,
        ).toBe('');
    });

    it('마감일 자체의 시각을 우선하며 일정 날짜에만 있는 분 단위 시각도 보존한다', () => {
        const schedules = [
            {
                id: 'closing',
                title: '신청 접수 마감',
                date: '2026-12-31T18:30:00',
                evidence,
            },
        ];
        expect(noticeDetailDates(notice({ schedules })).end.time).toBe('18:30');
        expect(
            noticeDetailDates(
                notice({ endDate: '2026-12-31T17:45:00', schedules }),
            ).end.time,
        ).toBe('17:45');
    });

    it('미확인 접수기간은 확인 필요로 표시하고 발표·서류 마감은 공란으로 둔다', () => {
        const result = noticeDetailDates(
            notice({ startDate: null, endDate: null, schedules: [] }),
        );
        for (const field of [result.start, result.end]) {
            expect(field).toEqual({ date: '확인 필요', time: '' });
        }
        for (const field of [result.announcement, result.documents]) {
            expect(field).toEqual({ date: '', time: '' });
        }
        expect(result.period).toEqual({
            values: ['확인 필요'],
            label: '접수 기간 확인 필요',
            note: '',
        });
    });

    it.each([
        {
            startDate: '2026-01-02',
            endDate: null,
            values: ['1/2 시작'],
            label: '2026.01.02 시작, 마감 미정',
            note: '마감 미정',
        },
        {
            startDate: null,
            endDate: '2026-12-31',
            values: ['12/31 마감'],
            label: '2026.12.31 마감, 시작 미정',
            note: '시작 미정',
        },
    ])(
        '접수기간 한쪽만 확인되면 날짜와 미확정 경계를 구분한다: $note',
        (row) => {
            const result = noticeDetailDates(
                notice({ startDate: row.startDate, endDate: row.endDate }),
            );
            expect(result.period).toEqual({
                values: row.values,
                label: row.label,
                note: row.note,
            });
        },
    );

    it('양쪽 날짜가 확인된 접수기간의 기존 월/일 표시를 유지한다', () => {
        expect(noticeDetailDates(notice()).period).toEqual({
            values: ['1/2', '–', '12/31'],
            label: '2026.01.02부터 2026.12.31까지',
            note: '',
        });
    });

    it('검수된 발표일과 서류 마감일이 있으면 실제 날짜와 시각을 유지한다', () => {
        const result = noticeDetailDates(
            notice({
                schedules: [
                    {
                        id: 'announcement',
                        title: '당첨자 발표',
                        date: '2027-01-06',
                        evidence,
                    },
                    {
                        id: 'documents',
                        title: '서류 제출 마감 (18:00)',
                        date: '2027-01-12',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.announcement).toEqual({ date: '2027.01.06', time: '' });
        expect(result.documents).toEqual({ date: '2027.01.12', time: '18:00' });
    });

    it.each(['REVIEWING', 'UNSUPPORTED', 'CHANGE_REVIEW'] as const)(
        '%s 준비 안내 상태와 무관하게 기본 검수를 통과한 공개 일정을 표시한다',
        (guideState) => {
            const result = noticeDetailDates(
                notice({
                    guideState,
                    schedules: [
                        {
                            id: 'announcement',
                            title: '서류 대상자 발표',
                            date: '2027-01-06',
                            evidence,
                        },
                        {
                            id: 'documents',
                            title: '서류 제출 마감 (18:00)',
                            date: '2027-01-12',
                            evidence,
                        },
                    ],
                }),
            );
            expect(result.announcement).toEqual({
                date: '2027.01.06',
                time: '',
            });
            expect(result.documents).toEqual({
                date: '2027.01.12',
                time: '18:00',
            });
            expect(result.start.date).toBe('2026.01.02');
        },
    );
});

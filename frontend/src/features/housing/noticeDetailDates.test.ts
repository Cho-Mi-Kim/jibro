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
    it('검수된 선정 방식의 선착순은 표시하되 무주택 예외나 선착순 부정 문구는 사용하지 않는다', () => {
        const source = notice({
            qualificationSummary: {
                eligibility: '',
                selection: '방문 접수 선착순으로 선정해요.',
                detail: null,
                evidence: null,
                reviewRequired: false,
            },
        });
        expect(noticeDetailDates(source).announcement).toEqual({
            date: '선착순',
            time: '',
        });
        expect(noticeDetailDates(source).documents.date).toBe('선착순');
        expect(
            noticeDetailDates({
                ...source,
                qualificationSummary: {
                    ...source.qualificationSummary!,
                    selection: '선착순이 아닌 추첨으로 선정해요.',
                },
            }).announcement.date,
        ).toBe('');
        expect(
            noticeDetailDates(
                notice({
                    summary: '선착순 공급받은 분양권은 무주택 예외예요.',
                }),
            ).announcement.date,
        ).toBe('');
        expect(noticeDetailDates(source).start.date).toBe('2026.01.02');
        expect(noticeDetailDates(source).end.date).toBe('2026.12.31');
    });
    it('상시 접수의 마감 조건을 날짜 없이 표시하고 확정된 마감일을 우선한다', () => {
        const rolling = notice({
            startDate: null,
            endDate: null,
            schedules: [
                {
                    id: 'rolling',
                    title: '접수 회차 · 모집 완료 시 마감',
                    date: '',
                    timingDescription: '평일 방문 09:30~17:30',
                    evidence,
                },
            ],
        });
        expect(noticeDetailDates(rolling).showPeriod).toBe(true);
        expect(noticeDetailDates(rolling).period.values).toEqual([
            '모집 완료 시 마감',
        ]);
        expect(
            noticeDetailDates({ ...rolling, startDate: '2026-08-12' }).period
                .note,
        ).toBe('모집 완료 시 마감');
        expect(
            noticeDetailDates({ ...rolling, endDate: '2026-12-30' }).period
                .values,
        ).toEqual(['12/30 마감']);
        expect(
            noticeDetailDates({ ...rolling, status: 'CLOSED' }).period.label,
        ).not.toContain('모집 완료');
        expect(
            noticeDetailDates(notice({ title: '상시모집', endDate: null }))
                .period.note,
        ).toBe('마감 미정');
    });
    it('후순위 회차의 마감 시각은 접수 조건과 함께 표시한다', () => {
        const result = noticeDetailDates(
            notice({
                endDate: '2026-12-03',
                schedules: [
                    {
                        id: 'end',
                        title: '신청 접수 마감 (16:00 · 후순위 접수 시)',
                        date: '2026-12-03T16:00',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.end.time).toBe('16:00');
        expect(result.period.note).toContain('후순위');
        expect(result.period.label).toContain(result.period.note);
    });

    it('원문이 마감일 24:00이면 그 날짜 바로 아래에 24:00을 표시한다', () => {
        const result = noticeDetailDates(
            notice({
                endDate: '2026-12-03',
                applicationTiming: {
                    startsAt: null,
                    endsAt: '2026-12-03T15:00:00Z',
                    nextChangeAt: null,
                },
                schedules: [
                    {
                        id: 'auto-application-end',
                        title: '신청 접수 마감 (24:00)',
                        date: '2026-12-03',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.end).toEqual({ date: '2026.12.03', time: '24:00' });
        expect(result.period.label).toContain('2026.12.03 24:00까지');
    });

    it('근거가 같은 당일 회차는 한 줄에 묶고 공식 자정 표기를 유지한다', () => {
        const result = noticeDetailDates(
            notice({
                schedules: [
                    {
                        id: 'auto-scoped-1-end',
                        title: '가단지 온라인 접수 종료 (24:00)',
                        date: '2026-12-01',
                        evidence,
                    },
                    {
                        id: 'auto-scoped-1-start',
                        title: '가단지 온라인 접수 시작',
                        date: '2026-12-01T10:00',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.sessions.map((session) => session.title)).toEqual([
            '가단지 온라인 10:00~24:00',
        ]);
    });

    it('공단에서 확인한 여러 날의 방문 회차가 전체 기간과 같은 시각을 표시한다', () => {
        const starts = '2026-11-23T09:00';
        const ends = '2026-11-25T18:00';
        const prefix =
            '접수 회차 · 주민센터 방문 (점심 12:00~13:00 제외) 접수 ';
        const result = noticeDetailDates(
            notice({
                startDate: '2026-11-23',
                endDate: '2026-11-25',
                applicationTiming: {
                    startsAt: starts + '+09:00',
                    endsAt: ends + '+09:00',
                    nextChangeAt: null,
                },
                schedules: [
                    {
                        id: 'auto-scoped-1-end',
                        title: prefix + '마감',
                        date: ends,
                        evidence,
                    },
                    {
                        id: 'auto-scoped-1-start',
                        title: prefix + '시작',
                        date: starts,
                        evidence,
                    },
                ],
            }),
        );
        expect(result.start.time).toBe('09:00');
        expect(result.end.time).toBe('18:00');
        expect(result.sessions).toHaveLength(1);
        expect(result.sessions[0]).toMatchObject({
            date: '2026-11-23',
            title: '주민센터 방문 (점심 12:00~13:00 제외) · 09:00 시작, 2026.11.25 18:00 마감',
            evidence,
        });
    });

    it('공단 회차의 시작과 마감 근거가 다르면 각 시각과 근거를 따로 표시한다', () => {
        const closing = { ...evidence, url: 'https://www.kohom.or.kr/' };
        const result = noticeDetailDates(
            notice({
                schedules: [
                    {
                        id: 'auto-scoped-1-start',
                        title: '접수 회차 · 방문 접수 시작',
                        date: '2026-11-23T09:00',
                        evidence,
                    },
                    {
                        id: 'auto-scoped-1-end',
                        title: '접수 회차 · 방문 접수 마감',
                        date: '2026-11-25T18:00',
                        evidence: closing,
                    },
                ],
            }),
        );
        expect(
            result.sessions.map(({ date, title, evidence }) => [
                date,
                title,
                evidence,
            ]),
        ).toEqual([
            ['2026-11-23', '방문 접수 시작 09:00', evidence],
            ['2026-11-25', '방문 접수 마감 18:00', closing],
        ]);
    });

    it('자동 검수 회차에도 날짜와 해당 회차의 한국 시각을 함께 표시한다', () => {
        const result = noticeDetailDates(
            notice({
                schedules: [
                    {
                        id: 'auto-scoped-1-start',
                        title: '1순위 · 온라인 접수 시작',
                        date: '2026-12-02T00:00:00Z',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.sessions[0]).toMatchObject({
            date: '2026-12-02',
            title: '1순위 · 온라인 접수 시작 09:00',
            evidence,
        });
    });

    it('단지별 접수 시각은 회차별로 표시하고 전체 마감으로 해석하지 않는다', () => {
        const result = noticeDetailDates(
            notice({
                startDate: null,
                endDate: null,
                schedules: [
                    {
                        id: 'session',
                        title: '접수 회차 · 고창 온라인 10:00~24:00',
                        date: '2026-12-01',
                        evidence,
                    },
                ],
            }),
        );
        expect(result.showPeriod).toBe(false);
        expect(result.end.time).toBe('');
        expect(result.sessions[0]).toMatchObject({
            title: '고창 온라인 10:00~24:00',
            date: '2026-12-01',
            evidence,
        });
    });

    it('같은 일정이라도 현재 공고는 검수된 접수 경계를, 이전 기록은 저장 당시 일정만 사용한다', () => {
        const schedules = [
            {
                id: 'end',
                title: '신청 접수 마감 (18:00)',
                date: '2026-11-13',
                evidence,
            },
            {
                id: 'docs',
                title: '서류 제출 마감',
                date: '2026-11-15T16:30:00Z',
                evidence,
            },
            {
                id: 'ann',
                title: '결과 발표 · 개별 안내',
                date: '',
                timingDescription: '선정 후 우편 통지',
                evidence,
            },
        ];
        const saved = noticeDetailDates({ schedules });
        expect(saved.period.values).toEqual(['11/13 마감']);
        expect(saved.end.time).toBe('18:00');
        expect(saved.documents).toEqual({ date: '2026.11.16', time: '01:30' });
        expect(saved.announcement.date).toBe('');
        expect(saved.announcementExplanation?.timingDescription).toBe(
            '선정 후 우편 통지',
        );
        const current = noticeDetailDates(
            notice({
                schedules,
                startDate: null,
                endDate: '2026-11-13',
                applicationTiming: {
                    startsAt: null,
                    endsAt: null,
                    nextChangeAt: null,
                },
            }),
        );
        expect(current.end.time).toBe('');
        expect(
            noticeDetailDates({
                schedules: [{ ...schedules[0], title: '2순위 접수 마감' }],
            }).period.values,
        ).toEqual(['확인 필요']);
    });
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

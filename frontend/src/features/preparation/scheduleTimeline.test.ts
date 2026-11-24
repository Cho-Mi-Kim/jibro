import { describe, expect, it } from 'vitest';
import type { Notice, Preparation, Schedule } from '../housing/types';
import { buildScheduleTimeline } from './scheduleTimelineModel';
import { calendarItems } from './calendar';
import { nextNoticeSchedule } from '../housing/noticeDisplay';
import {
    currentPreparationSchedules,
    currentPreparationScheduleSource,
} from '../housing/schedules';

const now = new Date('2026-11-06T00:00:00+09:00');
const evidence = {
    label: '공고문 일정',
    url: 'https://apply.lh.or.kr/',
    checkedAt: '2026-11-01',
};
const notice: Notice = {
    id: 'one',
    title: '청년 주거 공고',
    agency: 'LH',
    region: '전국',
    type: '전세임대',
    status: 'OPEN',
    startDate: '2026-02-24',
    endDate: '2026-12-31',
    publishedDate: '2026-02-24',
    attachments: [],
    summary: '',
    version: 3,
    guideVersion: 3,
    guideState: 'AVAILABLE',
    canStart: true,
    evidence,
    questions: [],
    documents: [],
};
const preparation: Preparation = {
    id: 'prep',
    noticeId: notice.id,
    title: notice.title,
    version: 3,
    revision: 1,
    guideState: 'AVAILABLE',
    questions: [],
    answers: {},
    documents: [],
    schedules: [],
    progress: 0,
    houseStage: 0,
    warnings: [],
    nextAction: { label: '', reason: '', path: '' },
};
function event(id: string, title: string, date: string): Schedule {
    return { id, title, date, recorded: false, evidence };
}
describe('전체 일정 표시 모델', () => {
    it('세부 일정은 날짜와 시작 시각순으로 정렬하고 같은 시각의 순서와 원래 저장값은 유지한다', () => {
        const schedules = [
            event('start', '신청 접수 시작 (14:00)', '2026-11-10'),
            event('middle', '접수 회차 · 현장 접수 14:00~16:00', '2026-11-24'),
            {
                ...event('end', '신청 접수 마감 (16:00)', '2026-12-08'),
                recorded: true,
            },
            event(
                'first-round',
                '접수 회차 · 현장 접수 14:00~16:00',
                '2026-11-10',
            ),
            event(
                'last-round',
                '접수 회차 · 현장 접수 14:00~16:00',
                '2026-12-08',
            ),
        ];
        const before = structuredClone(schedules);
        const { details } = buildScheduleTimeline(
            { ...notice, schedules },
            { ...preparation, schedules },
            now,
        );
        expect(details.map((s) => s.id)).toEqual([
            'start',
            'first-round',
            'middle',
            'last-round',
            'end',
        ]);
        expect(details.find((s) => s.id === 'end')?.recorded).toBe(true);
        expect(schedules).toEqual(before);
    });

    it('한국 시각으로 날짜를 비교하고 날짜 미확인 일정은 순서를 유지해 마지막에 둔다', () => {
        const schedules = [
            event('unknown', '결과 개별 안내', ''),
            event('local', '계약 안내', '2026-11-10T09:00:00'),
            event('utc', '서류 제출 마감', '2026-11-09T15:30:00Z'),
            event('invalid', '추가 안내', '2026-02-30'),
        ];
        expect(
            buildScheduleTimeline(
                undefined,
                { ...preparation, schedules },
                now,
            ).details.map((s) => s.id),
        ).toEqual(['utc', 'local', 'unknown', 'invalid']);
    });

    it.each([
        { kind: 'start', label: '시작', dateKey: 'startDate' },
        { kind: 'end', label: '마감', dateKey: 'endDate' },
    ] as const)(
        '같은 $label 날짜의 회차 중 시각 미확인이 있으면 대표 시각을 단정하지 않고 확인된 회차 시각은 유지한다',
        ({ kind, label, dateKey }) => {
            const schedules = [
                event(
                    'basic',
                    `신청 접수 ${label} (16:00)`,
                    '2026-11-19T16:00:00',
                ),
                event(
                    'known',
                    `접수 회차 · 온라인 접수 ${label} · A단지`,
                    '2026-11-19T16:00:00',
                ),
                event(
                    'unknown',
                    `접수 회차 · 온라인 접수 ${label} · B단지`,
                    '2026-11-19',
                ),
            ];
            const p: Preparation = {
                ...preparation,
                schedules,
                questions: [
                    {
                        id: 'method',
                        title: '어떤 방법으로 신청하나요?',
                        reason: '공식 방법',
                        evidence,
                        options: [
                            { value: 'ONLINE', label: '온라인 신청' },
                            { value: 'VISIT', label: '현장 신청' },
                        ],
                    },
                ],
                answers: { method: 'ONLINE' },
            };
            const n: Notice = { ...notice, schedules, [dateKey]: '2026-11-19' };
            const timeline = buildScheduleTimeline(n, p, now);
            expect(timeline.rows.find((row) => row.id === kind)).toMatchObject({
                date: '2026-11-19',
                time: '',
            });
            expect(
                timeline.details.map(({ id, date }) => ({ id, date })),
            ).toEqual([
                { id: 'basic', date: '2026-11-19' },
                { id: 'unknown', date: '2026-11-19' },
                { id: 'known', date: '2026-11-19T16:00:00' },
            ]);
            expect(
                calendarItems(n, p).find((item) => item.id === kind)?.date,
            ).toBe('2026-11-19');
            expect(
                calendarItems(n, p).find((item) => item.id === 'schedule-known')
                    ?.date,
            ).toBe('2026-11-19T16:00:00');
            expect(n.schedules?.[0].date).toBe('2026-11-19T16:00:00');
        },
    );

    it('선택 방법의 마지막 날을 대표 마감·기본 일정·다음 일정·캘린더에 함께 반영하고 저장 완료는 보존한다', () => {
        const schedules = [
            event('end', '신청 접수 마감 (18:00)', '2026-11-20T18:00:00'),
            event(
                'online-end',
                '접수 회차 · 온라인 접수 마감',
                '2026-11-19T16:00:00',
            ),
            event(
                'visit-end',
                '접수 회차 · 현장 접수 마감',
                '2026-11-20T18:00:00',
            ),
        ];
        const p: Preparation = {
            ...preparation,
            schedules: schedules.map((s) => ({
                ...s,
                recorded: s.id === 'end',
            })),
            questions: [
                {
                    id: 'method',
                    title: '어떤 방법으로 신청하나요?',
                    reason: '공식 방법',
                    evidence,
                    options: [
                        { value: 'ONLINE', label: '온라인 신청' },
                        { value: 'VISIT', label: '현장 신청' },
                        { value: 'UNKNOWN', label: '잘 모르겠어요' },
                    ],
                },
            ],
            answers: { method: 'ONLINE' },
        };
        const n: Notice = {
            ...notice,
            schedules,
            endDate: '2026-11-20',
            applicationTiming: {
                startsAt: '2026-02-24',
                endsAt: '2026-11-20T18:00:00',
                nextChangeAt: null,
            },
        };
        const source = currentPreparationScheduleSource(p, n);
        const timeline = buildScheduleTimeline(n, p, now);
        expect(timeline.rows.find((r) => r.id === 'end')).toMatchObject({
            dateLabel: '11월 19일',
            time: '16:00',
        });
        expect(timeline.details.find((s) => s.id === 'end')).toMatchObject({
            title: '신청 접수 마감',
            date: '2026-11-19T16:00:00',
            recorded: true,
        });
        expect(
            nextNoticeSchedule(source, currentPreparationSchedules(p, n), now)
                ?.date,
        ).toBe('2026-11-19T16:00:00');
        expect(
            calendarItems(n, p)
                .filter((s) => s.title.includes('마감'))
                .map((s) => s.date),
        ).toEqual(['2026-11-19T16:00:00', '2026-11-19T16:00:00']);
        expect(n.endDate).toBe('2026-11-20');
        expect(p.schedules[0]).toMatchObject({
            date: '2026-11-20T18:00:00',
            recorded: true,
        });

        const dateOnly = {
            ...n,
            schedules: schedules.map((s) =>
                s.id === 'online-end' ? { ...s, date: '2026-11-19' } : s,
            ),
        };
        expect(
            buildScheduleTimeline(dateOnly, p, now).rows.find(
                (r) => r.id === 'end',
            ),
        ).toMatchObject({ date: '2026-11-19', time: '' });
        expect(
            currentPreparationScheduleSource(p, dateOnly).schedules?.find(
                (s) => s.id === 'end',
            )?.title,
        ).toBe('신청 접수 마감');

        const missing = {
            ...n,
            schedules: schedules.filter((s) => s.id !== 'online-end'),
        };
        const incomplete = buildScheduleTimeline(missing, p, now);
        expect(incomplete.rows.find((r) => r.id === 'end')).toMatchObject({
            date: null,
            time: '',
            status: '확인 필요',
        });
        expect(incomplete.details.find((s) => s.id === 'end')).toMatchObject({
            date: '',
            recorded: true,
        });
        expect(
            calendarItems(missing, p).some((s) => s.title.includes('마감')),
        ).toBe(false);
    });

    it('접수 방법별 세부 일정과 요약 시간을 같은 답변으로 선택하고 기준 변경 시 필터를 해제한다', () => {
        const schedules = [
            event('visit', '접수 회차 · 현장 접수 시작', '2026-11-10T09:00:00'),
            event(
                'online',
                '접수 회차 · 온라인 접수 시작',
                '2026-11-10T10:00:00',
            ),
            event('common', '결과 발표', '2026-11-30'),
            event('contract', '접수 회차 · 현장 동호지정', '2026-12-01'),
        ];
        const p: Preparation = {
            ...preparation,
            schedules,
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
        };
        const n = { ...notice, schedules, startDate: '2026-11-10' };
        const timeline = buildScheduleTimeline(n, p, now);
        expect(timeline.details.map((s) => s.id)).toEqual([
            'online',
            'common',
            'contract',
        ]);
        expect(timeline.rows.find((r) => r.id === 'start')?.time).toBe('10:00');
        expect(
            buildScheduleTimeline({ ...n, version: 4 }, p, now).details.map(
                (s) => s.id,
            ),
        ).toEqual(['visit', 'online', 'common', 'contract']);
        expect(
            buildScheduleTimeline(
                n,
                { ...p, answers: { method: 'UNKNOWN' } },
                now,
            ).details.map((s) => s.id),
        ).toEqual(['visit', 'online', 'common', 'contract']);
        expect(p.schedules.map((s) => s.id)).toEqual([
            'visit',
            'online',
            'common',
            'contract',
        ]);
    });

    it('기본 날짜를 표시하고 알 수 없는 결과·서류 날짜는 다섯 칸 안에 남긴다', () => {
        const { rows } = buildScheduleTimeline(notice, preparation, now);
        expect(rows.map((row) => row.id)).toEqual([
            'published',
            'start',
            'end',
            'result',
            'documents',
        ]);
        expect(rows[0]).toMatchObject({
            dateLabel: '2월 24일',
            weekday: '화',
            status: '게시됨',
            tone: 'done',
        });
        expect(rows[1]).toMatchObject({ status: '접수 중', tone: 'active' });
        expect(rows[2]).toMatchObject({ status: 'D-55', time: '' });
        for (const row of rows.slice(3)) {
            expect(row).toMatchObject({
                date: null,
                dateLabel: '—',
                weekday: '',
                status: '확인 필요',
                tone: 'unknown',
            });
        }
    });
    it('같은 날의 마감 시각을 결합하고 추가 일정과 원문 안내는 세부 목록에 보존한다', () => {
        const schedules = [
            event(
                'closing',
                '신청 접수 마감 (18:00, 조기 중단 가능)',
                '2026-12-31',
            ),
            event('result', '서류 제출 대상자 발표', '2027-01-05'),
            event('docs', '서류 제출 마감', '2027-01-10T16:30:00'),
            event('extra', '계약 안내', '2027-01-20'),
        ];
        const { rows, details } = buildScheduleTimeline(
            { ...notice, schedules },
            { ...preparation, schedules },
            now,
        );
        expect(rows).toHaveLength(5);
        expect(rows[2]).toMatchObject({
            date: '2026-12-31T18:00:00',
            time: '18:00',
        });
        expect(rows[3]).toMatchObject({ dateLabel: '1월 5일', status: '예정' });
        expect(rows[4]).toMatchObject({ dateLabel: '1월 10일', time: '16:30' });
        expect(details).toEqual(
            schedules.map((s) => ({
                ...s,
            })),
        );
    });
    it.each([
        { ...notice, guideState: 'REVIEWING' as const },
        { ...notice, version: 4 },
    ])(
        '안내 검수 상태 또는 준비 버전과 무관하게 현재 공개 일정을 사용한다',
        (current) => {
            const oldSchedules = [event('result', '결과 발표', '2027-01-05')];
            const schedules = [event('result', '결과 발표', '2027-01-07')];
            const { rows, details } = buildScheduleTimeline(
                { ...current, schedules },
                { ...preparation, schedules: oldSchedules },
                now,
            );
            expect(rows[2].date).toBe('2026-12-31');
            expect(rows[3].date).toBe('2027-01-07');
            expect(details).toEqual(
                schedules.map((s) => ({
                    ...s,
                })),
            );
            expect(oldSchedules[0].date).toBe('2027-01-05');
        },
    );
    it('공고를 아직 읽지 못해도 검수된 준비 일정만 사용하고 없는 날짜를 만들지 않는다', () => {
        const schedules = [event('closing', '신청 접수 마감', '2026-12-31')];
        const model = buildScheduleTimeline(
            undefined,
            { ...preparation, schedules },
            now,
        );
        expect(model.rows[0].date).toBeNull();
        expect(model.rows[2]).toMatchObject({
            date: '2026-12-31',
            tone: 'active',
        });
        expect(model.checkedAt).toBe(evidence.checkedAt);
        expect(
            buildScheduleTimeline(
                undefined,
                { ...preparation, schedules, guideState: 'CHANGE_REVIEW' },
                now,
            ).details,
        ).toEqual([]);
    });
    it('완료 기록은 동일 기준의 같은 일정에만 표시하며 새 공고 기준으로 옮기지 않는다', () => {
        const saved = {
            ...event('closing', '신청 접수 마감', '2026-12-31'),
            recorded: true,
        };
        const record = { ...preparation, schedules: [saved] };
        const current = {
            ...notice,
            schedules: [{ ...saved, recorded: false }],
        };
        expect(
            buildScheduleTimeline(current, record, now).details[0].recorded,
        ).toBe(true);
        expect(
            buildScheduleTimeline({ ...current, version: 4 }, record, now)
                .details[0].recorded,
        ).toBe(false);
        expect(record.schedules[0].recorded).toBe(true);
    });
    it('유효하지 않은 날짜는 확인 필요이며 한국 날짜 기준 마감 당일과 이후를 구분한다', () => {
        const invalid = buildScheduleTimeline(
            {
                ...notice,
                publishedDate: '2026-02-30',
                startDate: 'bad',
                endDate: null,
            },
            preparation,
            now,
        );
        expect(
            invalid.rows
                .slice(0, 3)
                .every(
                    (row) => row.date === null && row.status === '확인 필요',
                ),
        ).toBe(true);
        const today = buildScheduleTimeline(
            { ...notice, endDate: '2026-11-06' },
            preparation,
            now,
        );
        expect(today.rows[1].status).toBe('접수 중');
        expect(today.rows[2].status).toBe('D-Day');
        const past = buildScheduleTimeline(
            { ...notice, endDate: '2026-11-05' },
            preparation,
            now,
        );
        expect(past.rows[1].status).toBe('접수 종료');
        expect(past.rows[2]).toMatchObject({ status: '마감', tone: 'done' });
    });
});

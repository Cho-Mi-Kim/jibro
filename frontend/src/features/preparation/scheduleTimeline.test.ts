import { describe, expect, it } from 'vitest';
import type { Notice, Preparation, Schedule } from '../housing/types';
import { buildScheduleTimeline } from './scheduleTimelineModel';

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
        expect(rows[2]).toMatchObject({ date: '2026-12-31', time: '18:00' });
        expect(rows[3]).toMatchObject({ dateLabel: '1월 5일', status: '예정' });
        expect(rows[4]).toMatchObject({ dateLabel: '1월 10일', time: '16:30' });
        expect(details).toEqual(schedules);
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
            expect(details).toEqual(schedules);
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

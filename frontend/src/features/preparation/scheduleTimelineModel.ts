import type { Evidence, Notice, Preparation, Schedule } from '../housing/types';
import { daysLeft } from '../../shared/ui/housingHelpers';
import {
    currentPreparationSchedules,
    scheduleDay,
    scheduleTime,
    selectSchedule,
} from '../housing/schedules';

type TimelineId = 'published' | 'start' | 'end' | 'result' | 'documents';
export interface TimelineRow {
    id: TimelineId;
    title: string;
    date: string | null;
    dateLabel: string;
    weekday: string;
    time: string;
    status: string;
    tone: 'done' | 'active' | 'pending' | 'unknown';
}

const titles: Record<TimelineId, string> = {
    published: '공고 게시',
    start: '신청 접수 시작',
    end: '신청 접수 마감',
    result: '서류 제출 대상자 발표',
    documents: '서류 제출 마감',
};

function validDate(value?: string | null) {
    return value && scheduleDay(value) ? value : null;
}

export function buildScheduleTimeline(
    notice: Notice | undefined,
    preparation: Preparation,
    now = new Date(),
): {
    rows: TimelineRow[];
    details: Schedule[];
    checkedAt?: string;
    evidence?: Evidence;
} {
    const sameBasis = !notice || notice.version === preparation.version;
    const details = currentPreparationSchedules(preparation, notice).map(
        (event): Schedule => ({
            ...event,
            recorded:
                sameBasis &&
                preparation.schedules.some(
                    (saved) =>
                        saved.id === event.id &&
                        saved.title === event.title &&
                        saved.date === event.date &&
                        saved.recorded,
                ),
        }),
    );
    const basic: Partial<Record<TimelineId, string | null>> = {
        published: notice?.publishedDate,
        start: notice?.startDate,
        end: notice?.endDate,
    };
    const rows = (Object.keys(titles) as TimelineId[]).map(
        (id): TimelineRow => {
            const basicDate = validDate(basic[id]);
            const event = selectSchedule(details, id, basicDate);
            const date = basicDate ?? validDate(event?.date);
            if (!date)
                return {
                    id,
                    title: titles[id],
                    date: null,
                    dateLabel: '—',
                    weekday: '',
                    time: '',
                    status: '확인 필요',
                    tone: 'unknown',
                };
            const day = scheduleDay(date)!;
            const time =
                scheduleTime({ date, title: event?.title ?? '' }) ||
                (event ? scheduleTime(event) : '');
            const left = daysLeft(date, now);
            const past = left === '일정 경과';
            const status =
                id === 'published'
                    ? past || left === 'D-Day'
                        ? '게시됨'
                        : '게시 예정'
                    : id === 'start'
                      ? past || left === 'D-Day'
                          ? '시작됨'
                          : '접수 예정'
                      : id === 'end'
                        ? past
                            ? '마감'
                            : left
                        : past
                          ? '일정 경과'
                          : left === 'D-Day'
                            ? '오늘'
                            : '예정';
            return {
                id,
                title: titles[id],
                date,
                dateLabel: `${Number(day.slice(5, 7))}월 ${Number(day.slice(8, 10))}일`,
                weekday: '일월화수목금토'[
                    new Date(day + 'T00:00:00Z').getUTCDay()
                ],
                time,
                status,
                tone:
                    past || (id === 'published' && left === 'D-Day')
                        ? 'done'
                        : 'pending',
            };
        },
    );
    const start = rows[1];
    const end = rows[2];
    if (
        start.date &&
        daysLeft(start.date, now) !== '일정 확인' &&
        (daysLeft(start.date, now) === '일정 경과' ||
            daysLeft(start.date, now) === 'D-Day')
    ) {
        if (end.date) {
            const closed = daysLeft(end.date, now) === '일정 경과';
            start.status = closed ? '접수 종료' : '접수 중';
            start.tone = closed ? 'done' : 'active';
        }
    }
    if (!rows.some((row) => row.tone === 'active')) {
        const upcoming = rows.find((row) => row.tone === 'pending');
        if (upcoming) upcoming.tone = 'active';
    }
    const evidence = notice?.evidence ?? details[0]?.evidence;
    return { rows, details, checkedAt: evidence?.checkedAt, evidence };
}

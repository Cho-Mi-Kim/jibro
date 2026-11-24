import { scheduleIsActive, lifecycleLabel } from '../housing/noticeLifecycle';
import type { Evidence, Notice, Preparation, Schedule } from '../housing/types';
import {
    individualAnnouncement,
    firstComeScheduleLabel,
} from '../housing/schedules';
import { applicationStatuses } from '../housing/noticeLabels';
import { daysLeft } from '../housing/noticeDisplay';
import {
    currentPreparationSchedules,
    currentPreparationScheduleSource,
    scheduleMilestone,
    scheduleDay,
    scheduleTime,
} from '../housing/schedules';

type TimelineId = 'published' | 'start' | 'end' | 'result' | 'documents';
export interface TimelineRow {
    timingLabel?: string;
    announcementExplanation?: Schedule;
    announcementAlternatives?: Omit<Schedule, 'recorded'>[];
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
        (event) => {
            const original =
                notice?.schedules?.find((item) => item.id === event.id) ??
                event;
            const saved = sameBasis
                ? preparation.schedules.find(
                      (item) =>
                          item.id === original.id &&
                          item.title === original.title &&
                          item.date === original.date,
                  )
                : undefined;
            return {
                ...event,
                recorded: saved?.recorded ?? false,
            };
        },
    );
    const source = currentPreparationScheduleSource(preparation, notice) ?? {
        title: preparation.title,
        schedules: details,
    };
    details.sort((a, b) => {
        const dayA = scheduleDay(a.date);
        const dayB = scheduleDay(b.date);
        if (!dayA || !dayB) return dayA ? -1 : dayB ? 1 : 0;
        return (
            dayA.localeCompare(dayB) ||
            scheduleTime(a)
                .padStart(5, '0')
                .localeCompare(scheduleTime(b).padStart(5, '0'))
        );
    });
    const resultExplanation = individualAnnouncement(details);
    const rows = (Object.keys(titles) as TimelineId[]).map(
        (id): TimelineRow => {
            const { date, alternatives } = scheduleMilestone(source, id);
            const timingLabel = firstComeScheduleLabel(source, id);
            if (timingLabel)
                return {
                    id,
                    title: titles[id],
                    date: null,
                    dateLabel: timingLabel,
                    timingLabel,
                    weekday: '',
                    time: '',
                    status: '',
                    tone: 'pending',
                };
            if (!date)
                return {
                    announcementAlternatives: alternatives,
                    announcementExplanation:
                        id === 'result' ? resultExplanation : undefined,
                    id,
                    title:
                        id === 'result' &&
                        (resultExplanation || alternatives.length)
                            ? '결과 발표'
                            : titles[id],
                    date: null,
                    dateLabel: '—',
                    weekday: '',
                    time: '',
                    status: alternatives.length
                        ? '대상별 안내'
                        : id === 'result' && resultExplanation
                          ? '개별 안내'
                          : '확인 필요',
                    tone: 'unknown',
                };
            const day = scheduleDay(date)!;
            const time = scheduleTime({ date, title: '' });
            const left =
                id === 'end' && notice?.status === 'CLOSED'
                    ? '일정 경과'
                    : daysLeft(date, now);
            const past = left === '일정 경과';
            const status =
                id === 'published'
                    ? past || left === 'D-Day'
                        ? '게시됨'
                        : '게시 예정'
                    : id === 'start'
                      ? past || left === 'D-Day'
                          ? '시작됨'
                          : applicationStatuses.UPCOMING.label
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
                announcementExplanation:
                    id === 'result' ? resultExplanation : undefined,
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
        (notice
            ? notice.status === 'OPEN' || notice.status === 'CLOSED'
            : daysLeft(start.date, now) === '일정 경과' ||
              daysLeft(start.date, now) === 'D-Day')
    ) {
        if (end.date) {
            const closed =
                notice?.status === 'CLOSED' ||
                daysLeft(end.date, now) === '일정 경과';
            start.status =
                applicationStatuses[closed ? 'CLOSED' : 'OPEN'].label;
            start.tone = closed ? 'done' : 'active';
        }
    }
    if (!rows.some((row) => row.tone === 'active')) {
        const upcoming = rows.find((row) => row.tone === 'pending');
        if (upcoming) upcoming.tone = 'active';
    }
    for (const row of rows) {
        if (row.id !== 'published' && !scheduleIsActive(notice, row.title)) {
            row.status = lifecycleLabel(notice);
            row.tone = 'unknown';
        }
    }
    const evidence = notice?.evidence ?? details[0]?.evidence;
    return { rows, details, checkedAt: evidence?.checkedAt, evidence };
}

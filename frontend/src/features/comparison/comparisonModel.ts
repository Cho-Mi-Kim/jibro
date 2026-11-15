import type { Evidence, Notice, Schedule } from '../housing/types';
import {
    dateWithScheduleTime,
    isBasicScheduleAlias,
    scheduleDay,
    selectSchedule,
} from '../housing/schedules';

export type ComparisonSlot = 'a' | 'b';
export const comparisonSlots = ['a', 'b'] as const;

export function selectedNotices(params: URLSearchParams) {
    const first = params.get('a') || undefined;
    const second = params.get('b') || undefined;
    return { a: first, b: second === first ? undefined : second };
}

export function selectComparisonNotice(
    params: URLSearchParams,
    slot: ComparisonSlot,
    id?: string,
) {
    const next = new URLSearchParams(params);
    const other = slot === 'a' ? 'b' : 'a';
    if (id && id !== next.get(other)) next.set(slot, id);
    else next.delete(slot);
    return next;
}

interface ComparisonSchedule {
    id: string;
    title: string;
    date: string | null;
    evidence?: Evidence;
}

export function comparisonSchedules(notice: Notice): ComparisonSchedule[] {
    const events = notice.schedules ?? [];
    const rows = [
        ['published', '공고일', notice.publishedDate],
        ['start', '접수 시작', notice.startDate],
        ['end', '접수 마감', notice.endDate],
        ['result', '결과 발표', null],
        ['documents', '서류 마감', null],
    ] as const;
    const represented = new Set<Omit<Schedule, 'recorded'>>();
    const result = rows.map(([kind, title, basicDate]): ComparisonSchedule => {
        const basic = basicDate && scheduleDay(basicDate) ? basicDate : null;
        const event = selectSchedule(events, kind, basic);
        if (event) {
            represented.add(event);
            if (kind === 'start' || kind === 'end')
                events
                    .filter((item) => isBasicScheduleAlias(item, event, kind))
                    .forEach((item) => represented.add(item));
        }
        const date = basic ?? event?.date ?? null;
        return {
            id: kind,
            title: event?.title ?? title,
            date: date ? dateWithScheduleTime(date, event) : null,
            evidence: event?.evidence ?? (basic ? notice.evidence : undefined),
        };
    });
    // Keep additional cohorts, stages, and detailed deadlines from the public source.
    return result.concat(
        events
            .filter((event) => !represented.has(event))
            .map((event) => ({
                ...event,
                id: 'detail:' + event.id,
                date: scheduleDay(event.date)
                    ? dateWithScheduleTime(event.date, event)
                    : null,
            })),
    );
}

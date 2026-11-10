import type { Notice, Preparation } from './types';

export type ScheduleKind =
    | 'published'
    | 'start'
    | 'end'
    | 'result'
    | 'documents'
    | 'application'
    | 'other';

export interface DatedSchedule {
    title: string;
    date: string;
}

export function scheduleDay(value: string) {
    if (!/^\d{4}-\d{2}-\d{2}(?:$|T| )/.test(value)) return null;
    const day = value.slice(0, 10);
    const check = new Date(day + 'T00:00:00Z');
    if (!Number.isFinite(+check) || check.toISOString().slice(0, 10) !== day)
        return null;
    if (value.length === 10) return day;
    const parsed = new Date(value);
    if (!Number.isFinite(+parsed)) return null;
    return /(?:Z|[+-]\d{2}:\d{2})$/.test(value)
        ? new Intl.DateTimeFormat('en-CA', {
              timeZone: 'Asia/Seoul',
              year: 'numeric',
              month: '2-digit',
              day: '2-digit',
          }).format(parsed)
        : day;
}

// Source titles are unstructured. Keep their interpretation at this boundary.
export function scheduleKind(title: string): ScheduleKind {
    if (/서류.*마감/.test(title)) return 'documents';
    if (/(?:대상|결과|당첨).*발표/.test(title)) return 'result';
    if (/공고.*(?:게시|발표)|공고일/.test(title)) return 'published';
    if (!/서류/.test(title)) {
        if (/공식\s*신청.*(?:여부|확인)|신청.*완료/.test(title))
            return 'application';
        if (/(?:신청|접수).*마감/.test(title)) return 'end';
        if (/(?:신청|접수).*(?:시작|개시)/.test(title)) return 'start';
    }
    return 'other';
}

export function currentPreparationSchedules(
    preparation: Preparation,
    notice?: Notice,
) {
    // Public official schedules belong to basic notice review, not preparation review.
    if (notice) return notice.schedules ?? [];
    return preparation.guideState === 'AVAILABLE' ? preparation.schedules : [];
}

function isBasicScheduleTitle(title: string, kind: ScheduleKind) {
    const plain = title
        .replace(/\([^)]*(?:\d{1,2}:\d{2}|조기\s*중단)[^)]*\)/g, '')
        .replace(/\s/g, '');
    return kind === 'end'
        ? /^(?:신청접수|신청|접수)마감$/.test(plain)
        : kind === 'start' &&
              /^(?:신청접수|신청|접수)(?:시작|개시)$/.test(plain);
}

export function isBasicScheduleAlias(
    event: DatedSchedule,
    selected: DatedSchedule,
    kind: 'start' | 'end',
) {
    if (event === selected) return true;
    if (
        scheduleKind(event.title) !== kind ||
        scheduleDay(event.date) !== scheduleDay(selected.date) ||
        !isBasicScheduleTitle(event.title, kind) ||
        !isBasicScheduleTitle(selected.title, kind)
    )
        return false;
    const time = scheduleTime(event);
    return !time || time === scheduleTime(selected);
}

export function scheduleTime(event: DatedSchedule) {
    if (/(?:Z|[+-]\d{2}:\d{2})$/.test(event.date) && scheduleDay(event.date))
        return new Intl.DateTimeFormat('en-GB', {
            timeZone: 'Asia/Seoul',
            hourCycle: 'h23',
            hour: '2-digit',
            minute: '2-digit',
        }).format(new Date(event.date));
    return (
        event.date.match(/[T ]([01]\d|2[0-3]):([0-5]\d)/)?.[0].slice(1) ??
        event.title.match(/\b([01]?\d|2[0-3]):([0-5]\d)\b/)?.[0] ??
        ''
    );
}

export function selectSchedule<T extends DatedSchedule>(
    schedules: readonly T[],
    kind: ScheduleKind,
    date?: string | null,
) {
    const targetDay = date ? scheduleDay(date) : null;
    if (date && !targetDay) return undefined;
    let candidates = schedules.filter(
        (event) =>
            scheduleKind(event.title) === kind &&
            !!scheduleDay(event.date) &&
            (!date || scheduleDay(event.date) === targetDay),
    );
    if (kind === 'result') {
        const documentResults = candidates.filter((event) =>
            /서류.*대상.*발표/.test(event.title),
        );
        if (documentResults.length) candidates = documentResults;
    } else if (kind === 'start' || kind === 'end') {
        const basic = candidates.filter((event) =>
            isBasicScheduleTitle(event.title, kind),
        );
        if (basic.length) candidates = basic;
    }
    return candidates.find((event) => scheduleTime(event)) ?? candidates[0];
}

export function applicationRecordSchedule<T extends DatedSchedule>(
    schedules: readonly T[],
) {
    return (
        selectSchedule(schedules, 'application') ??
        selectSchedule(schedules, 'end')
    );
}

// A basic date remains authoritative; a matching detailed event supplies time.
export function dateWithScheduleTime(date: string, event?: DatedSchedule) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !event) return date;
    if (scheduleDay(event.date) !== date) return date;
    if (event.date.length > 10) return event.date;
    const time = scheduleTime(event);
    return time ? `${date}T${time.padStart(5, '0')}:00` : date;
}

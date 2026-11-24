import type { Notice, Preparation, Schedule } from './types';
import {
    preparationScheduleSelection,
    reviewedPreparationScope,
    type PreparationScope,
} from './preparationApplicability';

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
    timingDescription?: string | null;
}

export type ScheduleSource = Partial<
    Pick<
        Notice,
        | 'publishedDate'
        | 'status'
        | 'lifecycle'
        | 'startDate'
        | 'endDate'
        | 'applicationTiming'
        | 'schedules'
        | 'title'
        | 'qualificationSummary'
    >
>;
export type MilestoneKind = Exclude<ScheduleKind, 'application' | 'other'>;

export const untilFilledLabel = '모집 완료 시 마감';

// Use recruitment/selection information, never a housing-ownership exception
// mentioning a previously purchased first-come unit.
export function firstComeScheduleLabel(
    source: ScheduleSource,
    kind: ScheduleKind,
) {
    if (kind !== 'result' && kind !== 'documents') return undefined;
    const selection = source.qualificationSummary?.selection ?? '';
    const text = [source.title, selection].filter(Boolean).join(' ');
    return /선착순/.test(text) &&
        !/(?:비선착순|선착순\s*(?:이\s*)?아(?:니|닌|닙)|선착순[^.!?\n]*않)/.test(
            text,
        )
        ? '선착순'
        : undefined;
}

export function closesWhenFilled(source: ScheduleSource) {
    if (
        source.status === 'CLOSED' ||
        (source.lifecycle && source.lifecycle.state !== 'NORMAL') ||
        scheduleMilestone(source, 'end').date
    )
        return false;
    return (
        source.applicationTiming?.untilFilled ??
        source.schedules?.some(
            (event) => event.title === '접수 회차 · ' + untilFilledLabel,
        ) ??
        false
    );
}

// A saved preparation has only its own events. A live notice also has
// reviewed application boundaries, including explicitly unknown dates.
export function scheduleMilestone(source: ScheduleSource, kind: MilestoneKind) {
    const events = source.schedules ?? [];
    const boundary = kind === 'start' || kind === 'end';
    const basic =
        kind === 'start'
            ? (source.applicationTiming?.startsAt ?? source.startDate)
            : kind === 'end'
              ? (source.applicationTiming?.endsAt ?? source.endDate)
              : kind === 'published'
                ? source.publishedDate
                : null;
    const hasPublicBoundary =
        boundary &&
        ((kind === 'start' ? 'startDate' : 'endDate') in source ||
            !!source.applicationTiming);
    const candidates =
        boundary && !hasPublicBoundary
            ? events.filter((event) => isBasicScheduleTitle(event.title, kind))
            : events;
    const results =
        kind === 'result' ? scheduleCandidates(candidates, kind) : [];
    const alternatives = distinctResultSchedules(results) ? results : [];
    const event =
        (hasPublicBoundary && !basic) || alternatives.length
            ? undefined
            : selectSchedule(candidates, kind, basic);
    const value = basic ?? event?.date;
    const date =
        value && scheduleDay(value)
            ? boundary && source.applicationTiming
                ? value
                : dateWithScheduleTime(value, event)
            : null;
    return { date, event, alternatives };
}

function distinctResultSchedules(events: readonly DatedSchedule[]) {
    if (events.length < 2) return false;
    const days = new Set(events.map((event) => scheduleDay(event.date)));
    const times = new Set(events.map(scheduleTime).filter(Boolean));
    // Ignore only the common announcement name and its clock. Keep every target
    // and qualification attached to the title instead of choosing one as global.
    const targets = new Set(
        events.map((event) =>
            event.title
                .replace(
                    /서류\s*제출\s*대상자?\s*발표|(?:입주자\s*)?선정\s*결과\s*발표|결과\s*발표|당첨자\s*발표|예비입주자\s*발표/g,
                    '',
                )
                .replace(/\b(?:[01]?\d|2[0-3]):[0-5]\d\b/g, '')
                .replace(/[\s·ㆍ⋅()[\]]/g, ''),
        ),
    );
    return days.size > 1 || times.size > 1 || targets.size > 1;
}

export function individualAnnouncement<T extends DatedSchedule>(
    schedules: readonly T[],
) {
    return schedules.find(
        (event) =>
            scheduleKind(event.title) === 'result' &&
            event.title.endsWith(' · 개별 안내') &&
            event.date === '' &&
            !!event.timingDescription,
    );
}

// The server resolves the reviewed application boundary; this only formats it.
export function applicationDate(
    notice: Pick<
        Notice,
        'startDate' | 'endDate' | 'applicationTiming' | 'schedules'
    >,
    kind: 'start' | 'end',
) {
    return scheduleMilestone(notice, kind).date;
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
    if (/(?:대상|결과|당첨|예비입주자).*발표/.test(title)) return 'result';
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
    if (notice) {
        return (
            currentPreparationScheduleSource(preparation, notice).schedules ??
            []
        );
    }
    return preparation.guideState === 'AVAILABLE'
        ? preparationScheduleSelection(preparation, preparation.schedules)
              .visible
        : [];
}

export function currentPreparationScheduleSource(
    preparation: Preparation,
    notice: Notice,
): Notice;
export function currentPreparationScheduleSource(
    preparation: Preparation,
    notice?: Notice,
): Notice | undefined;
export function currentPreparationScheduleSource(
    preparation: Preparation,
    notice?: Notice,
): Notice | undefined {
    if (
        !notice ||
        notice.version !== preparation.version ||
        notice.guideState !== 'AVAILABLE'
    )
        return notice;
    return {
        ...notice,
        ...personalPreparationScheduleSource(
            reviewedPreparationScope(preparation, notice),
            notice,
        ),
    };
}

export function snapshotPreparationScheduleSource(
    preparation: Preparation,
): Omit<ScheduleSource, 'schedules'> & { schedules: Schedule[] } {
    return personalPreparationScheduleSource(preparation, {
        title: preparation.title,
        schedules: preparation.schedules,
    });
}

function personalPreparationScheduleSource<
    T extends Omit<Schedule, 'recorded'>,
>(
    preparation: PreparationScope,
    source: Omit<ScheduleSource, 'schedules'> & { schedules?: T[] },
): Omit<ScheduleSource, 'schedules'> & { schedules: T[] } {
    const { visible, selected } = preparationScheduleSelection(
        preparation,
        source.schedules ?? [],
    );
    function selectedBoundary(kind: 'start' | 'end') {
        const candidates = selected.filter(
            (event) =>
                scheduleKind(event.title) === kind && scheduleDay(event.date),
        );
        const days = candidates.map((event) => scheduleDay(event.date)!).sort();
        const day = kind === 'start' ? days[0] : days.at(-1);
        if (!day) return null;
        const sameDay = candidates.filter(
            (event) => scheduleDay(event.date) === day,
        );
        // A known session time cannot stand in for another session's unknown time.
        const unknownTime = sameDay.find((event) => !scheduleTime(event));
        if (unknownTime) return { event: unknownTime, date: day };
        sameDay.sort((a, b) =>
            scheduleTime(a)
                .padStart(5, '0')
                .localeCompare(scheduleTime(b).padStart(5, '0')),
        );
        const boundary = kind === 'start' ? sameDay[0] : sameDay.at(-1)!;
        return {
            event: boundary,
            date: dateWithScheduleTime(boundary.date, boundary),
        };
    }
    const start = selectedBoundary('start');
    const end = selectedBoundary('end');
    const excluded = (source.schedules ?? []).filter(
        (event) => !visible.includes(event),
    );
    const unknownStart =
        !start &&
        excluded.some((event) => scheduleKind(event.title) === 'start');
    const unknownEnd =
        !end && excluded.some((event) => scheduleKind(event.title) === 'end');
    if (!start && !end && !unknownStart && !unknownEnd)
        return { ...source, schedules: visible };
    const originalStart = scheduleMilestone(source, 'start').date;
    const originalEnd = scheduleMilestone(source, 'end').date;
    const projected = visible.map((event) => {
        const kind = scheduleKind(event.title);
        const boundary = kind === 'start' ? start : kind === 'end' ? end : null;
        const unknown =
            kind === 'start' ? unknownStart : kind === 'end' && unknownEnd;
        if ((!boundary && !unknown) || !isBasicScheduleTitle(event.title, kind))
            return event;
        // Keep the common checklist identity while displaying its confirmed personal date.
        const title = event.title
            .replace(/\b\d{1,2}:\d{2}\b[,·]?\s*/g, '')
            .replace(/\(\s*\)/g, '')
            .trim();
        return {
            ...event,
            title,
            date: boundary?.date ?? '',
            evidence: boundary?.event.evidence ?? event.evidence,
        };
    });
    return {
        ...source,
        schedules: projected,
        startDate: start
            ? scheduleDay(start.date)
            : unknownStart
              ? null
              : (source.startDate ??
                (originalStart && scheduleDay(originalStart))),
        endDate: end
            ? scheduleDay(end.date)
            : unknownEnd
              ? null
              : (source.endDate ?? (originalEnd && scheduleDay(originalEnd))),
        applicationTiming: {
            startsAt: unknownStart ? null : (start?.date ?? originalStart),
            endsAt: unknownEnd ? null : (end?.date ?? originalEnd),
            nextChangeAt: source.applicationTiming?.nextChangeAt ?? null,
            untilFilled: end ? false : source.applicationTiming?.untilFilled,
        },
    };
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
    const candidates = scheduleCandidates(schedules, kind, date);
    return candidates.find((event) => scheduleTime(event)) ?? candidates[0];
}

function scheduleCandidates<T extends DatedSchedule>(
    schedules: readonly T[],
    kind: ScheduleKind,
    date?: string | null,
) {
    const targetDay = date ? scheduleDay(date) : null;
    if (date && !targetDay) return [];
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
    return candidates;
}

export function applicationRecordSchedule<T extends DatedSchedule>(
    schedules: readonly T[],
) {
    return (
        selectSchedule(schedules, 'application') ??
        selectSchedule(schedules, 'end')
    );
}

// Reception windows and sessions are information, not separate completed tasks.
export function canRecordSchedule(
    event: DatedSchedule & { id: string },
    schedules: readonly (DatedSchedule & { id: string })[],
) {
    const kind = scheduleKind(event.title);
    if (kind === 'application' || kind === 'end')
        return applicationRecordSchedule(schedules)?.id === event.id;
    return !(
        kind === 'start' ||
        (kind === 'other' &&
            /신청|접수/.test(event.title) &&
            !/서류/.test(event.title))
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

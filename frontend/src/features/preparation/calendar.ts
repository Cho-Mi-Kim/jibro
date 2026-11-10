import type { Evidence, Notice, Preparation } from '../housing/types';
import { safeUrl } from '../../shared/ui/helpers';
import {
    currentPreparationSchedules,
    dateWithScheduleTime,
    isBasicScheduleAlias,
    selectSchedule,
} from '../housing/schedules';
export interface CalendarItem {
    id: string;
    title: string;
    date: string;
    source: string;
}
export function calendarItems(n: Notice, p?: Preparation) {
    const items: CalendarItem[] = [];
    function add(id: string, title: string, date: string | null, ev: Evidence) {
        if (date && safeUrl(ev.url) && eventDate(date))
            items.push({ id, title, date, source: ev.url! });
    }
    const schedules = p
        ? currentPreparationSchedules(p, n)
        : (n.schedules ?? []);
    const selected = new Set<(typeof schedules)[number]>();
    for (const [kind, title, date] of [
        ['start', '신청 접수 시작', n.startDate],
        ['end', '신청 접수 마감', n.endDate],
    ] as const) {
        if (!date) continue;
        const event = selectSchedule(schedules, kind, date);
        const evidence =
            event && safeUrl(event.evidence.url) ? event.evidence : n.evidence;
        add(
            kind,
            event?.title ?? title,
            dateWithScheduleTime(date, event),
            evidence,
        );
        if (event)
            for (const row of schedules)
                if (isBasicScheduleAlias(row, event, kind)) selected.add(row);
    }
    for (const row of schedules)
        if (!selected.has(row))
            add(
                'schedule-' + row.id,
                row.title,
                dateWithScheduleTime(row.date, row),
                row.evidence,
            );
    return items;
}
function eventDate(value: string) {
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
        const parsed = new Date(value + 'T00:00:00Z');
        return !isNaN(+parsed) && parsed.toISOString().slice(0, 10) === value
            ? ';VALUE=DATE:' + value.replaceAll('-', '')
            : null;
    }
    if (
        !/^\d{4}-\d{2}-\d{2}T\d\d:\d\d(?::\d\d)?(?:Z|[+-]\d\d:\d\d)?$/.test(
            value,
        )
    )
        return null;
    const d = new Date(
        /(Z|[+-]\d\d:\d\d)$/.test(value) ? value : value + '+09:00',
    );
    return isNaN(+d) ? null : ':' + utc(d);
}
const utc = (d: Date) =>
    d
        .toISOString()
        .replace(/[-:]/g, '')
        .replace(/\.\d{3}/, '');
const escape = (v: string) =>
    v
        .replace(/\\/g, '\\\\')
        .replace(/\r\n|[\r\n]/g, '\\n')
        .replace(/[,;]/g, (c) => '\\' + c);
function fold(line: string) {
    const enc = new TextEncoder();
    let out = '',
        width = 0;
    for (const c of line) {
        const bytes = enc.encode(c).length;
        if (width + bytes > 75) {
            out += '\r\n ';
            width = 1;
        }
        out += c;
        width += bytes;
    }
    return out;
}
export function calendarFile(
    n: Notice,
    items: CalendarItem[],
    now = new Date(),
) {
    const lines = [
        'BEGIN:VCALENDAR',
        'VERSION:2.0',
        'PRODID:-//Jibro//Official schedules//KO',
        'CALSCALE:GREGORIAN',
    ];
    for (const e of items) {
        const start = eventDate(e.date);
        if (!start || !safeUrl(e.source)) continue;
        lines.push(
            'BEGIN:VEVENT',
            'UID:' +
                encodeURIComponent(n.id) +
                '-' +
                encodeURIComponent(e.id) +
                '@jibro.local',
            'SEQUENCE:' + n.version,
            'DTSTAMP:' + utc(now),
            'DTSTART' + start,
            'SUMMARY:' + escape(n.title + ' · ' + e.title),
            'DESCRIPTION:' +
                escape('공식 공고 기준 v' + n.version + '\n' + e.source),
            'URL:' + new URL(e.source).href,
            'END:VEVENT',
        );
    }
    lines.push('END:VCALENDAR');
    return lines.map(fold).join('\r\n') + '\r\n';
}

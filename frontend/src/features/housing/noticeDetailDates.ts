import { displayDate } from './noticeDisplay';
import {
    scheduleTime,
    scheduleMilestone,
    type ScheduleSource,
    individualAnnouncement,
    closesWhenFilled,
    untilFilledLabel,
    firstComeScheduleLabel,
} from './schedules';

function dateParts(value?: string | null, empty = '확인 필요') {
    if (!value) return { date: empty, time: '' };
    return {
        date: displayDate(value).slice(0, 10),
        time: scheduleTime({ date: value, title: '' }),
    };
}

export function shortNoticeDate(date: string) {
    const parts = date.match(/^\d{4}\.(\d{2})\.(\d{2})$/);
    return parts ? `${Number(parts[1])}/${Number(parts[2])}` : date;
}

function periodParts(
    start: string | null,
    end: string | null,
    short: boolean,
    endParts = dateParts(end),
    untilFilled = false,
) {
    const { date: first, time: startTime } = dateParts(start);
    const { date: last, time: endTime } = endParts;
    const firstLabel = [first, startTime].filter(Boolean).join(' ');
    const lastLabel = [last, endTime].filter(Boolean).join(' ');
    const format = (value: string) => (short ? shortNoticeDate(value) : value);
    if (start && end)
        return {
            values: [format(first), '–', format(last)],
            label: `${firstLabel}부터 ${lastLabel}까지`,
            note: '',
        };
    if (start || end) {
        const date = start ? first : last;
        const known = start ? '시작' : '마감';
        const note = start
            ? untilFilled
                ? untilFilledLabel
                : '마감 미정'
            : '시작 미정';
        return {
            values: [`${format(date)} ${known}`],
            label: `${start ? firstLabel : lastLabel} ${known}, ${note}`,
            note,
        };
    }
    return {
        values: [untilFilled ? untilFilledLabel : '확인 필요'],
        label: untilFilled ? untilFilledLabel : '접수 기간 확인 필요',
        note: '',
    };
}

export function noticeDetailDates(notice: ScheduleSource, short = true) {
    const schedules = notice.schedules ?? [];
    const { date: startValue } = scheduleMilestone(notice, 'start');
    const { date: endValue, event: closing } = scheduleMilestone(notice, 'end');
    const start = dateParts(startValue);
    const midnight = schedules.find(
        (event) =>
            event.id === 'auto-application-end' &&
            event.title.endsWith('(24:00)') &&
            endValue &&
            (endValue === event.date ||
                Date.parse(endValue) ===
                    Date.parse(event.date + 'T24:00:00+09:00')),
    );
    const end = midnight
        ? { date: dateParts(midnight.date).date, time: '24:00' }
        : dateParts(endValue);
    const byId = new Map(schedules.map((event) => [event.id, event]));
    const paired = new Map<string, (typeof schedules)[number]>();
    for (const event of schedules) {
        if (!/^auto-scoped-\d+-start$/.test(event.id)) continue;
        const last = byId.get(event.id.replace(/-start$/, '-end'));
        if (!last) continue;
        const scope = event.title.match(/^(.*) 접수 시작$/)?.[1];
        const closingScope = last.title.match(
            /^(.*) 접수 (?:종료|마감)(?: \(24:00\))?$/,
        )?.[1];
        if (
            scope &&
            scope === closingScope &&
            dateParts(event.date).time &&
            (dateParts(last.date).time || last.title.endsWith('(24:00)')) &&
            event.evidence.url === last.evidence.url &&
            event.evidence.label === last.evidence.label
        )
            paired.set(event.id, last);
    }
    const sessions = schedules
        .flatMap((event) => {
            const title = event.title.replace(/^접수 회차 · /, '');
            if (!event.id.startsWith('auto-scoped-'))
                return title === event.title ? [] : [{ ...event, title }];
            if (!event.date) return [{ ...event, title }];
            if (
                event.id.endsWith('-end') &&
                paired.has(event.id.replace(/-end$/, '-start'))
            )
                return [];
            const day = dateParts(event.date);
            const last = paired.get(event.id);
            let label = [title, day.time].filter(Boolean).join(' ');
            if (last) {
                const scope = title.replace(/ 접수 시작$/, '');
                const endDay = dateParts(last.date);
                const closes = last.title.endsWith('(24:00)')
                    ? '24:00'
                    : endDay.time;
                label =
                    day.date === endDay.date
                        ? `${scope} ${day.time}~${closes}`
                        : `${scope} · ${day.time} 시작, ${endDay.date} ${closes} 마감`;
            }
            return [
                { ...event, date: day.date.replaceAll('.', '-'), title: label },
            ];
        })
        .sort((a, b) => a.date.localeCompare(b.date));
    const announcement = scheduleMilestone(notice, 'result');
    const documents = scheduleMilestone(notice, 'documents');
    const untilFilled = closesWhenFilled(notice);
    const firstCome = firstComeScheduleLabel(notice, 'result');
    const period = periodParts(startValue, endValue, short, end, untilFilled);
    if (closing?.title.endsWith('후순위 접수 시)')) {
        period.note = '마감일은 후순위 접수가 열릴 때 기준';
        period.label += `, ${period.note}`;
    }
    return {
        start,
        end,
        closing: midnight ?? closing,
        period,
        sessions,
        showPeriod:
            !!startValue || !!endValue || sessions.length === 0 || untilFilled,
        announcement: firstCome
            ? { date: firstCome, time: '' }
            : dateParts(announcement.date, ''),
        announcementAlternatives: firstCome ? [] : announcement.alternatives,
        announcementExplanation: firstCome
            ? undefined
            : individualAnnouncement(schedules),
        documents: firstCome
            ? { date: firstCome, time: '' }
            : dateParts(documents.date, ''),
    };
}

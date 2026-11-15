import { displayDate } from './noticeDisplay';
import {
    applicationDate,
    scheduleTime,
    selectSchedule,
    individualAnnouncement,
} from './schedules';
import type { Notice } from './types';

function dateParts(value?: string | null, title = '', empty = '확인 필요') {
    if (!value) return { date: empty, time: '' };
    return {
        date: displayDate(value).slice(0, 10),
        time: scheduleTime({ date: value, title }),
    };
}

export function shortNoticeDate(date: string) {
    const parts = date.match(/^\d{4}\.(\d{2})\.(\d{2})$/);
    return parts ? `${Number(parts[1])}/${Number(parts[2])}` : date;
}

function periodParts(start?: string | null, end?: string | null) {
    const first = dateParts(start).date;
    const last = dateParts(end).date;
    if (start && end)
        return {
            values: [shortNoticeDate(first), '–', shortNoticeDate(last)],
            label: `${first}부터 ${last}까지`,
            note: '',
        };
    if (start || end) {
        const date = start ? first : last;
        const known = start ? '시작' : '마감';
        const note = start ? '마감 미정' : '시작 미정';
        return {
            values: [`${shortNoticeDate(date)} ${known}`],
            label: `${date} ${known}, ${note}`,
            note,
        };
    }
    return {
        values: ['확인 필요'],
        label: '접수 기간 확인 필요',
        note: '',
    };
}

export function applicationDeadline(notice: Notice) {
    return notice.endDate
        ? selectSchedule(notice.schedules ?? [], 'end', notice.endDate)
        : undefined;
}

export function noticeDetailDates(notice: Notice) {
    const schedules = notice.schedules ?? [];
    const closing = applicationDeadline(notice);
    const startValue = applicationDate(notice, 'start');
    const endValue = applicationDate(notice, 'end');
    const start = dateParts(startValue);
    const end = dateParts(
        endValue,
        notice.applicationTiming ? '' : closing?.title,
    );
    if (!notice.applicationTiming && !end.time && closing)
        end.time = dateParts(closing.date).time;
    const announcement = selectSchedule(schedules, 'result');
    const documents = selectSchedule(schedules, 'documents');
    return {
        start,
        end,
        period: periodParts(startValue, endValue),
        announcement: dateParts(announcement?.date, announcement?.title, ''),
        announcementExplanation: individualAnnouncement(schedules),
        documents: dateParts(documents?.date, documents?.title, ''),
    };
}

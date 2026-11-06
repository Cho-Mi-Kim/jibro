import { displayDate } from '../../shared/ui/housingHelpers';
import { scheduleTime, selectSchedule } from './schedules';
import type { Notice } from './types';

function dateParts(value?: string | null, title = '', empty = '확인 필요') {
    if (!value) return { date: empty, time: '' };
    return {
        date: displayDate(value).slice(0, 10),
        time: scheduleTime({ date: value, title }),
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
    const start = dateParts(notice.startDate);
    const end = dateParts(notice.endDate, closing?.title);
    if (!end.time && closing) end.time = dateParts(closing.date).time;
    const announcement = selectSchedule(schedules, 'result');
    const documents = selectSchedule(schedules, 'documents');
    return {
        start,
        end,
        announcement: dateParts(announcement?.date, announcement?.title, ''),
        documents: dateParts(documents?.date, documents?.title, ''),
    };
}

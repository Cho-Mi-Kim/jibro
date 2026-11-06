import type { Schedule } from '../../features/housing/types';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../features/housing/api';
import {
    scheduleDay,
    scheduleTime,
    selectSchedule,
} from '../../features/housing/schedules';
export function useNotice(id?: string) {
    return useQuery({
        queryKey: ['notice', id],
        queryFn: () => api.notice(id!),
        enabled: !!id,
    });
}
export function displayDate(value?: string | null) {
    if (!value) return '확인 필요';
    const day = scheduleDay(value);
    if (day) {
        const time = scheduleTime({ date: value, title: '' });
        return day.replaceAll('-', '.') + (time ? ' ' + time : '');
    }
    return value.replace(/-/g, '.').replace('T', ' ').slice(0, 16);
}
export function homeScheduleSummary(event: { title: string; date: string }) {
    const time = scheduleTime(event);
    const title = event.title
        .replace(/\([^)]*(?:\d{1,2}:\d{2}|조기\s*중단)[^)]*\)/g, '')
        .replace(/조기\s*중단\s*가능/g, '')
        .trim();
    const sourceDay = scheduleDay(event.date);
    const day = sourceDay
        ? sourceDay.slice(5, 10).replace('-', '.')
        : '확인 필요';
    return { title, date: day + (time ? ' ' + time : '') };
}
export function preparationDeadlineLabel(
    endDate: string | null | undefined,
    schedules: Pick<Schedule, 'title' | 'date'>[],
) {
    const parts =
        endDate && scheduleDay(endDate)?.match(/^\d{4}-(\d{2})-(\d{2})/);
    if (!parts) return '확인 필요';
    const closing = selectSchedule(schedules, 'end', endDate);
    const time =
        scheduleTime({
            date: endDate!,
            title: closing?.title ?? '',
        }) || (closing ? scheduleTime(closing) : '');
    return `${Number(parts[1])}월 ${Number(parts[2])}일${time ? ' ' + time : ''}`;
}
export function daysLeft(value?: string | null, now = new Date()) {
    if (!value) return '일정 확인';
    const day = scheduleDay(value);
    if (!day) return '일정 확인';
    const today = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Seoul',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
    }).format(now);
    const left = Math.round(
        (Date.parse(day + 'T00:00:00+09:00') -
            Date.parse(today + 'T00:00:00+09:00')) /
            86400000,
    );
    if (!Number.isFinite(left)) return '일정 확인';
    if (left < 0) return '일정 경과';
    return left === 0 ? 'D-Day' : 'D-' + left;
}

export function nextSchedule(
    schedules: Pick<Schedule, 'title' | 'date'>[],
    endDate?: string | null,
    now = new Date(),
) {
    const events =
        endDate && !selectSchedule(schedules, 'end', endDate)
            ? [...schedules, { title: '신청 접수 마감', date: endDate }]
            : schedules;
    return events
        .filter((event) => /^D-(?:Day|\d+)$/.test(daysLeft(event.date, now)))
        .sort((first, second) => first.date.localeCompare(second.date))[0];
}
